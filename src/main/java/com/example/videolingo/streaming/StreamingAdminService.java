package com.example.videolingo.streaming;

import com.example.videolingo.exception.AppException;
import com.example.videolingo.settings.Settings;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.streaming.SeriesReadiness.Problem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// RuengFlix as the admin portal sees it: which streaming collections are live and what to fix, viewer
// data (plays, votes, problem reports, Telegram followers, alerts) and the hero order. Viewer data is
// written by movie-api into the streaming_* tables (V17, V18); this reads it with plain SQL and makes
// the few admin changes (resolve a report, remove a follower, reset votes).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StreamingAdminService {

    public static final int MAX_FEATURED = 20;
    private static final int MAX_ROWS = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final RuengFlixClient ruengFlix;

    // ── shapes ────────────────────────────────────────────────────────────

    public record Totals(long starts, long completes, long likes, long dislikes) {
        static final Totals NONE = new Totals(0, 0, 0, 0);
    }

    public record LastAlert(int episode, String source, int sent, LocalDateTime at) {}

    public record SeriesSummary(
            long id,
            String slug,
            String title,
            String coverUrl,
            String visibility,
            String category,
            String status,
            boolean vip,
            int freeEpisodes,
            Integer featuredRank,
            int episodes,
            int scheduled,
            LocalDateTime nextEpisodeAt,
            Totals totals,
            long followers,
            long openReports,
            LastAlert lastAlert,
            SeriesReadiness.State state,
            List<Problem> problems) {}

    public record Overview(String siteUrl, boolean siteConnected, List<SeriesSummary> series) {}

    public record EpisodeRow(
            int number,
            long videoId,
            String title,
            Integer durationSeconds,
            boolean subtitled,
            // Has a Khmer voice-over track (generated or uploaded), which RuengFlix offers as "Khmer voice"
            boolean dubbed,
            boolean locked,
            long starts,
            long completes,
            long likes,
            long dislikes) {}

    public record SeriesDetail(
            String siteUrl, boolean siteConnected, SeriesSummary series, List<EpisodeRow> episodes) {}

    public record Report(
            long id,
            long seriesId,
            String seriesTitle,
            int episode,
            String reason,
            String note,
            int positionSeconds,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt,
            String resolvedBy) {}

    public record Follower(long chatId, LocalDateTime since) {}

    public record AlertLogRow(
            long id, int episode, String source, int followers, int sent, int removed, int failed, LocalDateTime at) {}

    public record Status(String siteUrl, List<RuengFlixClient.Check> checks) {}

    // ── series ────────────────────────────────────────────────────────────

    public Overview overview() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select c.id, c.slug, c.title, c.cover_url, c.visibility, c.series_category, c.series_status, c.vip,"
                        + " c.free_episodes, c.featured_rank, c.banner_url, c.vertical_url"
                        + " from collections c where c.streaming"
                        + " order by c.featured_rank nulls last, c.id desc",
                Map.of());
        List<Long> ids =
                rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        Map<Long, List<Item>> items = items(ids);
        Map<Long, Totals> totals = totals(ids);
        Map<Long, Long> followers = counts(
                "select collection_id, count(*) from streaming_follows where collection_id in (:ids) group by 1", ids);
        Map<Long, Long> open = counts(
                "select collection_id, count(*) from streaming_reports where resolved_at is null and collection_id in (:ids) group by 1",
                ids);
        Map<Long, LastAlert> alerts = lastAlerts(ids);
        List<SeriesSummary> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            out.add(summary(
                    r,
                    items.getOrDefault(id, List.of()),
                    totals.getOrDefault(id, Totals.NONE),
                    followers.getOrDefault(id, 0L),
                    open.getOrDefault(id, 0L),
                    alerts.get(id)));
        }
        return new Overview(ruengFlix.siteUrl(), ruengFlix.canControlSite(), out);
    }

    public SeriesDetail detail(long id) {
        Map<String, Object> row = requireSeries(id);
        List<Item> items = items(List.of(id)).getOrDefault(id, List.of());
        Map<Integer, Totals> perEpisode = new HashMap<>();
        jdbc.query(
                "select episode, starts, completes, likes, dislikes from streaming_episode_stats where collection_id = :id",
                Map.of("id", id),
                rs -> {
                    perEpisode.put(
                            rs.getInt(1), new Totals(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
                });
        Totals sum = perEpisode.values().stream()
                .reduce(
                        Totals.NONE,
                        (a, b) -> new Totals(
                                a.starts() + b.starts(),
                                a.completes() + b.completes(),
                                a.likes() + b.likes(),
                                a.dislikes() + b.dislikes()));
        long followers = counts(
                        "select collection_id, count(*) from streaming_follows where collection_id in (:ids) group by 1",
                        List.of(id))
                .getOrDefault(id, 0L);
        long open = counts(
                        "select collection_id, count(*) from streaming_reports where resolved_at is null and collection_id in (:ids) group by 1",
                        List.of(id))
                .getOrDefault(id, 0L);
        SeriesSummary summary = summary(
                row, items, sum, followers, open, lastAlerts(List.of(id)).get(id));

        boolean vip = Boolean.TRUE.equals(row.get("vip"));
        int free = ((Number) row.get("free_episodes")).intValue();
        List<EpisodeRow> episodes = new ArrayList<>();
        int number = 0;
        for (Item it : items) {
            if (!it.released()) continue;
            number++;
            Totals t = perEpisode.getOrDefault(number, Totals.NONE);
            episodes.add(new EpisodeRow(
                    number,
                    it.videoId(),
                    it.title(),
                    it.durationSeconds(),
                    it.subtitled(),
                    it.dubbed(),
                    vip && number > free,
                    t.starts(),
                    t.completes(),
                    t.likes(),
                    t.dislikes()));
        }
        return new SeriesDetail(ruengFlix.siteUrl(), ruengFlix.canControlSite(), summary, episodes);
    }

    /** Sets the hero order: the given series get ranks 1, 2, 3…, every other series none. */
    @Transactional
    public Overview setFeatured(List<Long> collectionIds) {
        List<Long> ids = collectionIds == null
                ? List.of()
                : collectionIds.stream().distinct().toList();
        if (ids.size() > MAX_FEATURED) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Pick at most " + MAX_FEATURED + " hero series");
        }
        if (!ids.isEmpty()) {
            Integer known = jdbc.queryForObject(
                    "select count(*) from collections where streaming and id in (:ids)",
                    Map.of("ids", ids),
                    Integer.class);
            if (known == null || known != ids.size()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Only collections shown on RuengFlix can be hero picks");
            }
        }
        jdbc.update("update collections set featured_rank = null where featured_rank is not null", Map.of());
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update(
                    "update collections set featured_rank = :rank where id = :id",
                    Map.of("rank", i + 1, "id", ids.get(i)));
        }
        return overview();
    }

    // ── votes ─────────────────────────────────────────────────────────────

    /** Clears 👍/👎 for one episode, or the whole series when {@code episode} is null; returns rows changed. */
    @Transactional
    public int resetVotes(long seriesId, Integer episode) {
        requireSeries(seriesId);
        MapSqlParameterSource p = new MapSqlParameterSource("id", seriesId);
        String sql = "update streaming_episode_stats set likes = 0, dislikes = 0 where collection_id = :id";
        if (episode != null) {
            sql += " and episode = :ep";
            p.addValue("ep", episode);
        }
        return jdbc.update(sql, p);
    }

    // ── reports ───────────────────────────────────────────────────────────

    public List<Report> reports(boolean includeResolved, Long seriesId) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", MAX_ROWS);
        StringBuilder sql = new StringBuilder(
                "select r.id, r.collection_id, c.title, r.episode, r.reason, r.note, r.position_seconds,"
                        + " r.created_at, r.resolved_at, r.resolved_by"
                        + " from streaming_reports r join collections c on c.id = r.collection_id where true");
        if (!includeResolved) sql.append(" and r.resolved_at is null");
        if (seriesId != null) {
            sql.append(" and r.collection_id = :sid");
            p.addValue("sid", seriesId);
        }
        sql.append(" order by r.resolved_at is not null, r.created_at desc limit :limit");
        return jdbc.query(
                sql.toString(),
                p,
                (rs, i) -> new Report(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getInt(7),
                        time(rs, 8),
                        time(rs, 9),
                        rs.getString(10)));
    }

    @Transactional
    public Report resolve(long reportId, boolean resolved, String actor) {
        int changed = resolved
                ? jdbc.update(
                        "update streaming_reports set resolved_at = now(), resolved_by = :by where id = :id",
                        Map.of("id", reportId, "by", actor == null ? "admin" : actor))
                : jdbc.update(
                        "update streaming_reports set resolved_at = null, resolved_by = null where id = :id",
                        Map.of("id", reportId));
        if (changed == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Report not found");
        }
        return reports(true, null).stream()
                .filter(r -> r.id() == reportId)
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Report not found"));
    }

    // ── followers and alerts ──────────────────────────────────────────────

    public List<Follower> followers(long seriesId) {
        requireSeries(seriesId);
        return jdbc.query(
                "select chat_id, created_at from streaming_follows where collection_id = :id order by created_at desc limit :limit",
                Map.of("id", seriesId, "limit", MAX_ROWS),
                (rs, i) -> new Follower(rs.getLong(1), time(rs, 2)));
    }

    @Transactional
    public void removeFollower(long seriesId, long chatId) {
        int removed = jdbc.update(
                "delete from streaming_follows where collection_id = :id and chat_id = :chat",
                Map.of("id", seriesId, "chat", chatId));
        if (removed == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "That chat doesn't follow this series");
        }
    }

    public List<AlertLogRow> alerts(long seriesId) {
        requireSeries(seriesId);
        return jdbc.query(
                "select id, episode, source, followers, sent, removed, failed, created_at from streaming_alert_log"
                        + " where collection_id = :id order by created_at desc, id desc limit 100",
                Map.of("id", seriesId),
                (rs, i) -> new AlertLogRow(
                        rs.getLong(1),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getInt(5),
                        rs.getInt(6),
                        rs.getInt(7),
                        time(rs, 8)));
    }

    /** Asks RuengFlix to alert the followers now, about {@code episode} or else the newest episode. */
    public Map<String, Object> sendAlert(long seriesId, Integer episode) {
        SeriesDetail d = detail(seriesId);
        if (d.series().state() != SeriesReadiness.State.LIVE) {
            throw new AppException(
                    HttpStatus.CONFLICT, "This series isn't live on RuengFlix, so there's nothing to announce");
        }
        int ep = episode != null ? episode : d.episodes().size();
        if (ep < 1 || ep > d.episodes().size()) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST,
                    "Episode must be between 1 and " + d.episodes().size());
        }
        Map<String, Object> result = new LinkedHashMap<>(ruengFlix.sendAlert(seriesId, ep));
        result.put("episode", ep);
        return result;
    }

    // ── viewer accounts ───────────────────────────────────────────────────

    // phone, telegramUsername and googleEmail are the viewer's ways in (V20); any of them can be null.
    // telegramLinked: signed in with Telegram (a username is optional on Telegram).
    public record ViewerRow(
            long id,
            String phone,
            String displayName,
            boolean disabled,
            LocalDateTime createdAt,
            LocalDateTime lastLoginAt,
            LocalDateTime librarySyncedAt,
            boolean telegramLinked,
            String telegramUsername,
            String googleEmail) {}

    public record ViewerPage(List<ViewerRow> items, long total, int page, int size) {}

    /**
     * Viewer accounts (V19, V20), newest first. {@code q} matches the name, Telegram username or Google
     * email, or the phone number however it's typed ("012 345", "85512…"). {@code status}: all, active or
     * disabled.
     */
    public ViewerPage viewers(String q, String status, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("limit", s).addValue("offset", (p - 1) * s);
        StringBuilder where = new StringBuilder(" where true");
        String query = q == null ? "" : q.strip();
        if (!query.isEmpty()) {
            String digits = ViewerPhones.searchDigits(query);
            where.append(" and (lower(v.display_name) like :name or lower(v.telegram_username) like :name"
                    + " or lower(v.google_email) like :name");
            params.addValue(
                    "name", "%" + query.toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
            if (!digits.isEmpty()) {
                where.append(" or v.phone like :digits");
                params.addValue("digits", "%" + digits + "%");
            }
            where.append(")");
        }
        if ("active".equals(status)) where.append(" and not v.disabled");
        else if ("disabled".equals(status)) where.append(" and v.disabled");
        Long total = jdbc.queryForObject("select count(*) from streaming_viewers v" + where, params, Long.class);
        List<ViewerRow> items = jdbc.query(
                VIEWER_SELECT
                        + " from streaming_viewers v left join streaming_viewer_libraries l on l.viewer_id = v.id"
                        + where
                        + " order by v.created_at desc, v.id desc limit :limit offset :offset",
                params,
                this::viewerRow);
        return new ViewerPage(items, total == null ? 0 : total, p, s);
    }

    public long viewerCount() {
        Long n = jdbc.queryForObject("select count(*) from streaming_viewers", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    /** Disabling also signs the viewer out of RuengFlix everywhere (their session version moves on). */
    @Transactional
    public ViewerRow setViewerDisabled(long id, boolean disabled) {
        int changed = jdbc.update(
                "update streaming_viewers set disabled = :d,"
                        + " session_version = session_version + case when :d then 1 else 0 end where id = :id",
                Map.of("d", disabled, "id", id));
        if (changed == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Viewer not found");
        }
        return jdbc.query(
                        VIEWER_SELECT
                                + " from streaming_viewers v left join streaming_viewer_libraries l on l.viewer_id = v.id"
                                + " where v.id = :id",
                        Map.of("id", id),
                        this::viewerRow)
                .get(0);
    }

    private static final String VIEWER_SELECT =
            "select v.id, v.phone, v.display_name, v.disabled, v.created_at, v.last_login_at, l.updated_at,"
                    + " v.telegram_id, v.telegram_username, v.google_email";

    private ViewerRow viewerRow(ResultSet rs, int i) throws SQLException {
        return new ViewerRow(
                rs.getLong(1),
                ViewerPhones.display(rs.getString(2)),
                rs.getString(3),
                rs.getBoolean(4),
                time(rs, 5),
                time(rs, 6),
                time(rs, 7),
                rs.getObject(8) != null,
                rs.getString(9),
                rs.getString(10));
    }

    // ── prices, status ────────────────────────────────────────────────────

    public SettingsService.SectionView prices() {
        return settings.view(SettingsService.Section.STREAMING);
    }

    @Transactional
    public SettingsService.SectionView updatePrices(Settings.Streaming values, Long version, String actor) {
        return settings.update(SettingsService.Section.STREAMING, values, version, actor);
    }

    public Status status() {
        return new Status(
                ruengFlix.siteUrl(), List.of(ruengFlix.movieApi(), ruengFlix.site(), ruengFlix.bucketPrivacy()));
    }

    public Map<String, Object> refreshSite() {
        return ruengFlix.refreshSite();
    }

    // ── internals ─────────────────────────────────────────────────────────

    record Item(
            long videoId,
            String title,
            Integer durationSeconds,
            boolean released,
            boolean scheduled,
            LocalDateTime publishAt,
            boolean subtitled,
            boolean dubbed) {}

    private Map<String, Object> requireSeries(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select c.id, c.slug, c.title, c.cover_url, c.visibility, c.series_category, c.series_status, c.vip,"
                        + " c.free_episodes, c.featured_rank, c.banner_url, c.vertical_url"
                        + " from collections c where c.id = :id and c.streaming",
                Map.of("id", id));
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "No collection #" + id + " is shown on RuengFlix");
        }
        return rows.get(0);
    }

    /** Each collection's videos in collection order, with what the site's rules make of them. */
    private Map<Long, List<Item>> items(List<Long> ids) {
        Map<Long, List<Item>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        LocalDateTime now = LocalDateTime.now();
        jdbc.query(
                "select ci.collection_id, v.id, v.title, v.duration_seconds, v.enabled, v.visibility,"
                        + " v.deleted_at is null and v.archived_at is null as live, v.publish_at,"
                        + " exists (select 1 from subtitles s where s.video_id = v.id and s.published"
                        + "   and (lower(s.language) = 'km' or s.is_default)) as subtitled,"
                        + " exists (select 1 from video_dubs d where d.video_id = v.id and d.language = 'km') as dubbed"
                        + " from collection_items ci join videos v on v.id = ci.video_id"
                        + " where ci.collection_id in (:ids) order by ci.collection_id, ci.position, ci.id",
                Map.of("ids", ids),
                rs -> {
                    int seconds = rs.getInt(4);
                    Integer duration = rs.wasNull() ? null : seconds;
                    boolean publicLive = rs.getBoolean(7) && "PUBLIC".equals(rs.getString(6));
                    boolean enabled = rs.getBoolean(5);
                    LocalDateTime publishAt = time(rs, 8);
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new Item(
                                    rs.getLong(2),
                                    rs.getString(3),
                                    duration,
                                    enabled && publicLive,
                                    !enabled && publicLive && publishAt != null && publishAt.isAfter(now),
                                    publishAt,
                                    rs.getBoolean(9),
                                    rs.getBoolean(10)));
                });
        return out;
    }

    private Map<Long, Totals> totals(List<Long> ids) {
        Map<Long, Totals> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query(
                "select collection_id, sum(starts), sum(completes), sum(likes), sum(dislikes)"
                        + " from streaming_episode_stats where collection_id in (:ids) group by 1",
                Map.of("ids", ids),
                rs -> {
                    out.put(rs.getLong(1), new Totals(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
                });
        return out;
    }

    private Map<Long, Long> counts(String sql, List<Long> ids) {
        Map<Long, Long> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query(sql, Map.of("ids", ids), rs -> {
            out.put(rs.getLong(1), rs.getLong(2));
        });
        return out;
    }

    private Map<Long, LastAlert> lastAlerts(List<Long> ids) {
        Map<Long, LastAlert> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query(
                "select distinct on (collection_id) collection_id, episode, source, sent, created_at"
                        + " from streaming_alert_log where collection_id in (:ids)"
                        + " order by collection_id, created_at desc, id desc",
                Map.of("ids", ids),
                rs -> {
                    out.put(rs.getLong(1), new LastAlert(rs.getInt(2), rs.getString(3), rs.getInt(4), time(rs, 5)));
                });
        return out;
    }

    private static SeriesSummary summary(
            Map<String, Object> r,
            List<Item> items,
            Totals totals,
            long followers,
            long openReports,
            LastAlert lastAlert) {
        List<Item> released = items.stream().filter(Item::released).toList();
        List<Item> scheduled = items.stream().filter(Item::scheduled).toList();
        int skipped = items.size() - released.size() - scheduled.size();
        LocalDateTime next = scheduled.stream()
                .map(Item::publishAt)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        Integer featured = r.get("featured_rank") == null ? null : ((Number) r.get("featured_rank")).intValue();
        String visibility = (String) r.get("visibility");
        String category = (String) r.get("series_category");
        boolean vip = Boolean.TRUE.equals(r.get("vip"));
        int free = ((Number) r.get("free_episodes")).intValue();
        SeriesReadiness.Result check = SeriesReadiness.check(new SeriesReadiness.Input(
                visibility,
                category,
                (String) r.get("cover_url"),
                (String) r.get("banner_url"),
                (String) r.get("vertical_url"),
                vip,
                free,
                featured,
                released.stream().map(Item::subtitled).toList(),
                skipped,
                scheduled.size()));
        return new SeriesSummary(
                ((Number) r.get("id")).longValue(),
                (String) r.get("slug"),
                (String) r.get("title"),
                (String) r.get("cover_url"),
                visibility,
                category,
                (String) r.get("series_status"),
                vip,
                free,
                featured,
                released.size(),
                scheduled.size(),
                next,
                totals,
                followers,
                openReports,
                lastAlert,
                check.state(),
                check.problems());
    }

    private static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
