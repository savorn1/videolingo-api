package com.example.videolingo.streaming;

import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.PipelineSteps;
import com.example.videolingo.storage.MediaUrls;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * RuengFlix marketing in the admin portal: sponsor ads viewers watch to unlock an episode, time-limited
 * promotions (a VIP series free for everyone, extra coins on packs), the clips viewers shared, and the
 * paywall funnel of a series (where viewers stop, how many see the unlock screen and how many pay).
 * Times are the database's wall clock, like every other RuengFlix time.
 */
@Service
@RequiredArgsConstructor
public class StreamingMarketingAdminService {

    private final NamedParameterJdbcTemplate jdbc;
    private final MediaUrls mediaUrls;
    private final PipelineSteps steps;

    // ── ads ───────────────────────────────────────────────────────────────

    public record AdRow(
            long id,
            String title,
            String videoUrl,
            String clickUrl,
            int seconds,
            boolean active,
            int weight,
            long started,
            long completed,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    public record AdRequest(
            String title, String videoUrl, String clickUrl, Integer seconds, Boolean active, Integer weight) {}

    private static final String AD_SELECT = """
            select a.id, a.title, a.video_url, a.click_url, a.seconds, a.active, a.weight,
                   (select count(*) from streaming_ad_views w where w.ad_id = a.id),
                   (select count(*) from streaming_ad_views w where w.ad_id = a.id and w.completed_at is not null),
                   a.created_by, a.created_at, a.updated_at
            from streaming_ads a
            """;

    public List<AdRow> ads() {
        return jdbc.query(AD_SELECT + " order by a.active desc, a.created_at desc", Map.of(), this::adRow);
    }

    public AdRow createAd(AdRequest req, String actor) {
        AdRequest r = validAd(req, null);
        Long id = jdbc.queryForObject("""
                insert into streaming_ads (title, video_url, click_url, seconds, active, weight, created_by,
                    created_at, updated_at)
                values (:title, :video, :click, :seconds, :active, :weight, :by, now(), now()) returning id
                """, adParams(r).addValue("by", actor), Long.class);
        return ad(id);
    }

    public AdRow updateAd(long id, AdRequest req) {
        AdRow current = ad(id);
        AdRequest r = validAd(req, current);
        jdbc.update("""
                update streaming_ads set title = :title, video_url = :video, click_url = :click, seconds = :seconds,
                    active = :active, weight = :weight, updated_at = now() where id = :id
                """, adParams(r).addValue("id", id));
        return ad(id);
    }

    public void deleteAd(long id) {
        if (jdbc.update("delete from streaming_ads where id = :id", Map.of("id", id)) == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Ad not found");
        }
    }

    private AdRow ad(long id) {
        return jdbc.query(AD_SELECT + " where a.id = :id", Map.of("id", id), this::adRow).stream()
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Ad not found"));
    }

    /** Fills gaps from the current ad when editing, then checks every field */
    static AdRequest validAd(AdRequest req, AdRow current) {
        if (req == null) throw new AppException(HttpStatus.BAD_REQUEST, "Nothing to save");
        String title = pick(req.title(), current == null ? null : current.title());
        String video = pick(req.videoUrl(), current == null ? null : current.videoUrl());
        String click = req.clickUrl() != null ? req.clickUrl().strip() : current == null ? null : current.clickUrl();
        int seconds = req.seconds() != null ? req.seconds() : current == null ? 15 : current.seconds();
        boolean active = req.active() != null ? req.active() : current == null || current.active();
        int weight = req.weight() != null ? req.weight() : current == null ? 1 : current.weight();
        if (title == null || title.isBlank() || title.length() > 120) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Give the ad a name (up to 120 characters)");
        }
        if (!isHttp(video)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Upload the ad video or paste its http(s) link");
        }
        if (click != null && !click.isEmpty() && !isHttp(click)) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "The \"Learn more\" link must start with http:// or https://");
        }
        if (seconds < 5 || seconds > 60) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Viewers watch between 5 and 60 seconds");
        }
        if (weight < 1 || weight > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Weight is from 1 to 100");
        }
        return new AdRequest(
                title.strip(), video.strip(), click == null || click.isEmpty() ? null : click, seconds, active, weight);
    }

    private MapSqlParameterSource adParams(AdRequest r) {
        return new MapSqlParameterSource()
                .addValue("title", r.title())
                .addValue("video", mediaUrls.toStored(r.videoUrl()))
                .addValue("click", r.clickUrl())
                .addValue("seconds", r.seconds())
                .addValue("active", r.active())
                .addValue("weight", r.weight());
    }

    private AdRow adRow(ResultSet rs, int i) throws SQLException {
        return new AdRow(
                rs.getLong(1),
                rs.getString(2),
                mediaUrls.forBrowser(rs.getString(3)),
                rs.getString(4),
                rs.getInt(5),
                rs.getBoolean(6),
                rs.getInt(7),
                rs.getLong(8),
                rs.getLong(9),
                rs.getString(10),
                time(rs, 11),
                time(rs, 12));
    }

    // ── promotions ────────────────────────────────────────────────────────

    public record PromotionRow(
            long id,
            String kind,
            String title,
            Long seriesId,
            String seriesTitle,
            Integer bonusPercent,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            String state,
            String createdBy,
            LocalDateTime createdAt) {}

    public record PromotionRequest(
            String kind,
            String title,
            Long seriesId,
            Integer bonusPercent,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {}

    private static final String PROMO_SELECT = """
            select p.id, p.kind, p.title, p.collection_id, c.title, p.bonus_percent, p.starts_at, p.ends_at,
                   case when p.ends_at <= localtimestamp then 'ENDED' when p.starts_at > localtimestamp then 'SCHEDULED'
                        else 'RUNNING' end,
                   p.created_by, p.created_at
            from streaming_promotions p left join collections c on c.id = p.collection_id
            """;

    /** Longest a promotion may run */
    static final Duration MAX_PROMOTION = Duration.ofDays(31);

    public List<PromotionRow> promotions() {
        return jdbc.query(
                PROMO_SELECT + " order by (p.ends_at <= localtimestamp), p.starts_at desc limit 200",
                Map.of(),
                this::promoRow);
    }

    public PromotionRow createPromotion(PromotionRequest req, String actor) {
        validPromotion(req);
        if ("FREE_SERIES".equals(req.kind())) {
            Map<String, Object> series = jdbc
                    .query(
                            "select c.vip from collections c where c.id = :id and c.streaming = true",
                            Map.of("id", req.seriesId()),
                            (rs, i) -> Map.<String, Object>of("vip", rs.getBoolean(1)))
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "Pick a series that is on RuengFlix"));
            if (!Boolean.TRUE.equals(series.get("vip"))) {
                throw new AppException(HttpStatus.BAD_REQUEST, "That series is already free: pick a VIP series");
            }
        }
        Long id = jdbc.queryForObject(
                """
                insert into streaming_promotions (kind, title, collection_id, bonus_percent, starts_at, ends_at,
                    created_by, created_at)
                values (:kind, :title, :series, :bonus, :starts, :ends, :by, now()) returning id
                """,
                new MapSqlParameterSource()
                        .addValue("kind", req.kind())
                        .addValue("title", req.title().strip())
                        .addValue("series", "FREE_SERIES".equals(req.kind()) ? req.seriesId() : null)
                        .addValue("bonus", "COIN_BONUS".equals(req.kind()) ? req.bonusPercent() : null)
                        .addValue("starts", Timestamp.valueOf(req.startsAt()))
                        .addValue("ends", Timestamp.valueOf(req.endsAt()))
                        .addValue("by", actor),
                Long.class);
        return jdbc.query(PROMO_SELECT + " where p.id = :id", Map.of("id", id), this::promoRow)
                .get(0);
    }

    /** End a running promotion now, or remove one that hasn't started (or has ended) */
    public void stopPromotion(long id) {
        int ended = jdbc.update(
                "update streaming_promotions set ends_at = localtimestamp where id = :id"
                        + " and starts_at <= localtimestamp and ends_at > localtimestamp",
                Map.of("id", id));
        if (ended == 0 && jdbc.update("delete from streaming_promotions where id = :id", Map.of("id", id)) == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Promotion not found");
        }
    }

    static void validPromotion(PromotionRequest r) {
        if (r == null
                || r.kind() == null
                || !List.of("FREE_SERIES", "COIN_BONUS").contains(r.kind())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose \"Series free\" or \"Extra coins\"");
        }
        if (r.title() == null || r.title().isBlank() || r.title().length() > 120) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "Give the promotion a title for the site's banner (up to 120 characters)");
        }
        if ("FREE_SERIES".equals(r.kind()) && r.seriesId() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Pick the series to make free");
        }
        if ("COIN_BONUS".equals(r.kind())
                && (r.bonusPercent() == null || r.bonusPercent() < 1 || r.bonusPercent() > 200)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Extra coins are from 1% to 200%");
        }
        if (r.startsAt() == null || r.endsAt() == null || !r.endsAt().isAfter(r.startsAt())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The end must be after the start");
        }
        if (Duration.between(r.startsAt(), r.endsAt()).compareTo(MAX_PROMOTION) > 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A promotion can run for up to 31 days");
        }
        if (!r.endsAt().isAfter(LocalDateTime.now())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "That window is already over");
        }
    }

    private PromotionRow promoRow(ResultSet rs, int i) throws SQLException {
        return new PromotionRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, Long.class),
                rs.getString(5),
                rs.getObject(6, Integer.class),
                time(rs, 7),
                time(rs, 8),
                rs.getString(9),
                rs.getString(10),
                time(rs, 11));
    }

    // ── clips ─────────────────────────────────────────────────────────────

    public record ClipRow(
            long id,
            String code,
            long seriesId,
            String seriesTitle,
            int episode,
            int startSeconds,
            int seconds,
            String status,
            String url,
            String posterUrl,
            String error,
            int views,
            String viewerName,
            LocalDateTime createdAt) {}

    public List<ClipRow> clips() {
        return jdbc.query(
                """
                select k.id, k.code, k.collection_id, c.title, k.episode, k.start_seconds, k.seconds, k.status, k.url,
                       k.poster_url, k.error, k.views, v.display_name, k.created_at
                from streaming_clips k join collections c on c.id = k.collection_id
                left join streaming_viewers v on v.id = k.viewer_id
                order by k.created_at desc limit 200
                """,
                Map.of(),
                (rs, i) -> new ClipRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getInt(6),
                        rs.getInt(7),
                        rs.getString(8),
                        mediaUrls.forBrowser(rs.getString(9)),
                        mediaUrls.forBrowser(rs.getString(10)),
                        rs.getString(11),
                        rs.getInt(12),
                        rs.getString(13),
                        time(rs, 14)));
    }

    /** Take a shared clip down (its link stops working) and delete its files */
    public void deleteClip(long id) {
        List<String> keys = jdbc.query(
                "select storage_key from streaming_clips where id = :id", Map.of("id", id), (rs, i) -> rs.getString(1));
        if (keys.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "Clip not found");
        jdbc.update("delete from streaming_clips where id = :id", Map.of("id", id));
        String key = keys.get(0);
        if (key != null) {
            steps.deleteStored(key);
            steps.deleteStored(key.replaceAll("\\.mp4$", ".jpg"));
        }
    }

    // ── paywall funnel ────────────────────────────────────────────────────

    public record FunnelEpisode(
            int episode,
            boolean premium,
            long starts,
            long completes,
            long wallViews,
            long coinUnlocks,
            long adUnlocks,
            long adsStarted) {}

    public record Funnel(
            long seriesId,
            String title,
            boolean vip,
            int freeEpisodes,
            List<FunnelEpisode> episodes,
            long seriesPurchases,
            BigDecimal seriesRevenueUsd,
            long coinsSpent) {}

    /** Per episode: plays, finishes, unlock-screen views and unlocks; plus whole-series sales */
    public Funnel funnel(long seriesId) {
        Map<String, Object> series = jdbc
                .query(
                        "select c.title, c.vip, c.free_episodes from collections c where c.id = :id",
                        Map.of("id", seriesId),
                        (rs, i) -> Map.<String, Object>of(
                                "title", rs.getString(1), "vip", rs.getBoolean(2), "free", rs.getInt(3)))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Series not found"));
        Map<String, Object> id = Map.of("id", seriesId);
        Map<Integer, long[]> stats = new HashMap<>();
        jdbc.query(
                "select episode, starts, completes, wall_views from streaming_episode_stats where collection_id = :id",
                id,
                rs -> {
                    stats.put(rs.getInt(1), new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                });
        Map<Integer, long[]> unlocks = new HashMap<>();
        jdbc.query(
                "select episode, count(*) filter (where source = 'COINS'), count(*) filter (where source = 'AD')"
                        + " from streaming_entitlements where collection_id = :id and episode > 0 group by 1",
                id,
                rs -> {
                    unlocks.put(rs.getInt(1), new long[] {rs.getLong(2), rs.getLong(3)});
                });
        Map<Integer, Long> ads = new HashMap<>();
        jdbc.query("select episode, count(*) from streaming_ad_views where collection_id = :id group by 1", id, rs -> {
            ads.put(rs.getInt(1), rs.getLong(2));
        });
        int last = 0;
        for (int n : stats.keySet()) last = Math.max(last, n);
        for (int n : unlocks.keySet()) last = Math.max(last, n);
        Integer released = jdbc.queryForObject("""
                select count(*) from collection_items i join videos v on v.id = i.video_id
                where i.collection_id = :id and v.enabled and v.visibility = 'PUBLIC' and v.deleted_at is null
                      and v.archived_at is null
                """, id, Integer.class);
        last = Math.max(last, released == null ? 0 : released);
        boolean vip = (Boolean) series.get("vip");
        int free = (Integer) series.get("free");
        List<FunnelEpisode> episodes = new java.util.ArrayList<>();
        for (int n = 1; n <= last; n++) {
            long[] s = stats.getOrDefault(n, new long[3]);
            long[] u = unlocks.getOrDefault(n, new long[2]);
            episodes.add(new FunnelEpisode(n, vip && n > free, s[0], s[1], s[2], u[0], u[1], ads.getOrDefault(n, 0L)));
        }
        Map<String, Object> sales = jdbc.queryForMap(
                "select count(*), coalesce(sum(amount_usd), 0) from streaming_orders"
                        + " where kind = 'SERIES' and status = 'PAID' and collection_id = :id",
                id);
        Long coins = jdbc.queryForObject(
                "select coalesce(-sum(delta), 0) from streaming_coin_ledger where reason = 'UNLOCK' and ref like :ref",
                Map.of("ref", "series:" + seriesId + ":ep:%"),
                Long.class);
        return new Funnel(
                seriesId,
                (String) series.get("title"),
                vip,
                free,
                episodes,
                ((Number) sales.get("count")).longValue(),
                (BigDecimal) sales.get("coalesce"),
                coins == null ? 0 : coins);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String pick(String given, String current) {
        return given != null ? given : current;
    }

    static boolean isHttp(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI u = URI.create(url.strip());
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
