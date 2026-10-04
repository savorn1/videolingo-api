package com.example.videolingo.analytics;

import com.example.videolingo.dto.AnalyticsDtos.AiAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.AiFeatureRow;
import com.example.videolingo.dto.AnalyticsDtos.AiVideoRow;
import com.example.videolingo.dto.AnalyticsDtos.Amount;
import com.example.videolingo.dto.AnalyticsDtos.DayValue;
import com.example.videolingo.dto.AnalyticsDtos.Delta;
import com.example.videolingo.dto.AnalyticsDtos.LanguageAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.LanguagePair;
import com.example.videolingo.dto.AnalyticsDtos.LanguageRow;
import com.example.videolingo.dto.AnalyticsDtos.LargeFile;
import com.example.videolingo.dto.AnalyticsDtos.Range;
import com.example.videolingo.dto.AnalyticsDtos.Share;
import com.example.videolingo.dto.AnalyticsDtos.StorageAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.TopVideo;
import com.example.videolingo.dto.AnalyticsDtos.TopViewer;
import com.example.videolingo.dto.AnalyticsDtos.TranslationAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.UserAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.VideoAnalytics;
import com.example.videolingo.dto.AnalyticsDtos.WatchAnalytics;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.settings.SettingsService;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Read-only aggregates for the Analytics pages. Plain SQL over the existing
// tables (nothing is pre-aggregated), one method per area. Dates are the
// server's local dates — the same clock the timestamps were written with.
//
// Period params in every query: :a/:b = [from, to + 1 day) and :pa/:pb = the
// same-length period right before it.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    public static final int MAX_DAYS = 366;
    private static final int DEFAULT_DAYS = 30;
    private static final int TOP = 10;
    private static final String[] WEEKDAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    // "Translation" = a transcript in any language other than the video's spoken one.
    private static final String IS_TRANSLATION = "v.language is not null and lower(t.language) <> lower(v.language)";

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    /** Resolved period + previous period, and the bound parameters for both. */
    record Period(Range range, MapSqlParameterSource params) {}

    public Period period(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "'from' must be on or before 'to'");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose a range of at most " + MAX_DAYS + " days");
        }
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("a", start.atStartOfDay())
                .addValue("b", end.plusDays(1).atStartOfDay())
                .addValue("pa", prevStart.atStartOfDay())
                .addValue("pb", start.atStartOfDay());
        return new Period(new Range(start, end, prevStart, prevEnd, (int) days), p);
    }

    // ── Users ─────────────────────────────────────────────────────────────

    public UserAnalytics users(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        return new UserAnalytics(
                p.range(),
                count("select count(*) from users", p),
                count("select count(*) from users where enabled", p),
                count("select count(*) from users where not enabled", p),
                count("select count(*) from users where role = 'ADMIN'", p),
                count("select count(*) from users where last_login_at is null", p),
                delta("select count(*) from users where created_at >= :a and created_at < :b", p),
                delta(
                        "select count(distinct user_id) from video_views where user_id is not null and viewed_at >= :a and viewed_at < :b",
                        p),
                count("select count(*) from users where last_login_at >= :a and last_login_at < :b", p),
                daily(
                        "select cast(created_at as date) d, count(*) v from users where created_at >= :a and created_at < :b group by 1",
                        p),
                daily("""
                        select cast(viewed_at as date) d, count(distinct user_id) v from video_views
                        where user_id is not null and viewed_at >= :a and viewed_at < :b group by 1""", p),
                shares("select role k, role l, count(*) c from users group by role order by c desc", p),
                shares("""
                        select coalesce(cast(cr.id as varchar), 'none') k, coalesce(cr.name, 'No custom role') l, count(*) c
                        from users u left join custom_roles cr on cr.id = u.custom_role_id
                        where u.role = 'USER' group by cr.id, cr.name order by c desc""", p),
                jdbc.query(
                        """
                        select u.id, u.username, count(*) views, coalesce(sum(vv.watched_seconds), 0) secs,
                               sum(case when vv.completed then 1 else 0 end) done
                        from video_views vv join users u on u.id = vv.user_id
                        where vv.viewed_at >= :a and vv.viewed_at < :b
                        group by u.id, u.username order by secs desc, views desc limit %d""".formatted(TOP),
                        p.params(),
                        (rs, i) -> new TopViewer(
                                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))));
    }

    // ── Videos ────────────────────────────────────────────────────────────

    public VideoAnalytics videos(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        Map<String, String> names = languageNames();
        return new VideoAnalytics(
                p.range(),
                count("select count(*) from videos where deleted_at is null", p),
                count("select count(*) from videos where deleted_at is null and enabled", p),
                count("select count(*) from videos where deleted_at is null and not enabled", p),
                count("select count(*) from videos where deleted_at is not null", p),
                delta("select count(*) from videos where created_at >= :a and created_at < :b", p),
                count("select coalesce(sum(duration_seconds), 0) from videos where deleted_at is null", p),
                number(
                        "select coalesce(avg(duration_seconds), 0) from videos where deleted_at is null and duration_seconds is not null",
                        p),
                count("""
                        select count(*) from videos v where v.deleted_at is null
                        and not exists (select 1 from transcripts t where t.video_id = v.id and t.segment_count > 0)""", p),
                count(
                        "select count(*) from videos v where v.deleted_at is null and not exists (select 1 from subtitles s where s.video_id = v.id)",
                        p),
                count(
                        "select count(*) from videos v where v.deleted_at is null and not exists (select 1 from video_categories vc where vc.video_id = v.id)",
                        p),
                count(
                        "select count(*) from videos v where v.deleted_at is null and not exists (select 1 from video_views vv where vv.video_id = v.id)",
                        p),
                daily(
                        "select cast(created_at as date) d, count(*) v from videos where created_at >= :a and created_at < :b group by 1",
                        p),
                labelled(
                        shares(
                                "select coalesce(language, '') k, coalesce(language, '') l, count(*) c from videos where deleted_at is null group by 1 order by c desc",
                                p),
                        names),
                shares("""
                        select cast(c.id as varchar) k, c.name l, count(*) cnt from video_categories vc
                        join categories c on c.id = vc.category_id join videos v on v.id = vc.video_id
                        where v.deleted_at is null group by c.id, c.name order by cnt desc""", p),
                ordered(shares("""
                        select case when duration_seconds is null then 'unknown' when duration_seconds < 60 then 'lt1'
                                    when duration_seconds < 300 then '1to5' when duration_seconds < 900 then '5to15'
                                    when duration_seconds < 1800 then '15to30' else '30plus' end k, '' l, count(*) c
                        from videos where deleted_at is null group by 1""", p), new String[][] {
                    {"lt1", "Under 1 min"},
                    {"1to5", "1–5 min"},
                    {"5to15", "5–15 min"},
                    {"15to30", "15–30 min"},
                    {"30plus", "30 min +"},
                    {"unknown", "Unknown"}
                }),
                topVideos(p));
    }

    private List<TopVideo> topVideos(Period p) {
        return jdbc.query(
                """
                select v.id, v.title, count(*) views, count(distinct vv.user_id) viewers, coalesce(sum(vv.watched_seconds), 0) secs,
                       avg(case when vv.completed then 1.0 else 0.0 end) done
                from video_views vv join videos v on v.id = vv.video_id
                where vv.viewed_at >= :a and vv.viewed_at < :b
                group by v.id, v.title order by views desc, secs desc limit %d""".formatted(TOP),
                p.params(),
                (rs, i) -> new TopVideo(
                        rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getDouble(6)));
    }

    // ── Watch ─────────────────────────────────────────────────────────────

    public WatchAnalytics watch(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        String inRange = " from video_views where viewed_at >= :a and viewed_at < :b";
        Double percent = nullableNumber("""
                select avg(least(cast(vv.watched_seconds as float) / v.duration_seconds, 1.0))
                from video_views vv join videos v on v.id = vv.video_id
                where vv.viewed_at >= :a and vv.viewed_at < :b and v.duration_seconds > 0""", p);

        List<Share> byHour = new ArrayList<>();
        Map<String, Long> hours = keyedCounts(
                "select cast(extract(hour from viewed_at) as int) k, count(*) c" + inRange + " group by 1", p);
        for (int h = 0; h < 24; h++) {
            byHour.add(new Share(
                    String.valueOf(h), String.format("%02d:00", h), hours.getOrDefault(String.valueOf(h), 0L)));
        }
        List<Share> byWeekday = new ArrayList<>();
        Map<String, Long> weekdays = keyedCounts(
                "select cast(extract(isodow from viewed_at) as int) k, count(*) c" + inRange + " group by 1", p);
        for (int d = 1; d <= 7; d++) {
            byWeekday.add(new Share(String.valueOf(d), WEEKDAYS[d - 1], weekdays.getOrDefault(String.valueOf(d), 0L)));
        }
        Map<String, String> names = languageNames();
        List<Amount> byLanguage = jdbc.query(
                """
                select coalesce(v.language, '') k, coalesce(sum(vv.watched_seconds), 0) secs, count(*) c
                from video_views vv join videos v on v.id = vv.video_id
                where vv.viewed_at >= :a and vv.viewed_at < :b group by 1 order by secs desc""",
                p.params(),
                (rs, i) -> new Amount(
                        rs.getString(1), languageLabel(rs.getString(1), names), rs.getDouble(2), rs.getLong(3)));

        return new WatchAnalytics(
                p.range(),
                delta("select count(*)" + inRange, p),
                delta("select coalesce(sum(watched_seconds), 0)" + inRange, p),
                delta("select count(distinct user_id)" + inRange + " and user_id is not null", p),
                delta("select coalesce(avg(case when completed then 1.0 else 0.0 end), 0)" + inRange, p),
                count("select count(*)" + inRange + " and user_id is null", p),
                number("select coalesce(avg(watched_seconds), 0)" + inRange, p),
                percent,
                daily("select cast(viewed_at as date) d, count(*) v" + inRange + " group by 1", p),
                daily(
                        "select cast(viewed_at as date) d, coalesce(sum(watched_seconds), 0) v" + inRange
                                + " group by 1",
                        p),
                byHour,
                byWeekday,
                byLanguage,
                topVideos(p));
    }

    // ── Translations ──────────────────────────────────────────────────────

    public TranslationAnalytics translations(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        Map<String, String> names = languageNames();
        String live = " from transcripts t join videos v on v.id = t.video_id where v.deleted_at is null and "
                + IS_TRANSLATION;
        long translations = count("select count(*)" + live, p);
        long liveVideos = count("select count(*) from videos where deleted_at is null", p);

        String jobs = " from processing_jobs where type = 'TRANSLATE' and created_at >= :a and created_at < :b";
        long succeeded = count("select count(*)" + jobs + " and status = 'SUCCEEDED'", p);
        long failed = count("select count(*)" + jobs + " and status = 'FAILED'", p);

        Map<String, Long> perVideo = keyedCounts("""
                select case when n = 0 then '0' when n = 1 then '1' when n = 2 then '2' else '3+' end k, count(*) c from (
                  select v.id, count(t.id) n from videos v
                  left join transcripts t on t.video_id = v.id and %s
                  where v.deleted_at is null group by v.id) x group by 1""".formatted(IS_TRANSLATION), p);
        List<Share> coverage = new ArrayList<>();
        for (String[] b : new String[][] {
            {"0", "Not translated"}, {"1", "1 language"}, {"2", "2 languages"}, {"3+", "3+ languages"}
        }) {
            coverage.add(new Share(b[0], b[1], perVideo.getOrDefault(b[0], 0L)));
        }

        return new TranslationAnalytics(
                p.range(),
                translations,
                delta(
                        "select count(*) from transcripts t join videos v on v.id = t.video_id where " + IS_TRANSLATION
                                + " and t.created_at >= :a and t.created_at < :b",
                        p),
                count("select count(distinct t.video_id)" + live, p),
                count(
                        "select count(distinct t.video_id) from transcripts t join videos v on v.id = t.video_id where v.deleted_at is null",
                        p),
                liveVideos,
                liveVideos == 0 ? 0 : (double) translations / liveVideos,
                count("select coalesce(sum(t.word_count), 0)" + live, p),
                count(
                        "select count(*) from subtitles t join videos v on v.id = t.video_id where v.deleted_at is null and "
                                + IS_TRANSLATION,
                        p),
                ordered(shares("select status k, '' l, count(*) c" + jobs + " group by status", p), new String[][] {
                    {"SUCCEEDED", "Succeeded"},
                    {"RUNNING", "Running"},
                    {"QUEUED", "Queued"},
                    {"FAILED", "Failed"},
                    {"CANCELLED", "Cancelled"}
                }),
                succeeded + failed == 0 ? null : (double) succeeded / (succeeded + failed),
                nullableNumber(
                        "select avg(extract(epoch from finished_at - started_at))" + jobs
                                + " and status = 'SUCCEEDED' and started_at is not null and finished_at is not null",
                        p),
                daily(
                        "select cast(t.created_at as date) d, count(*) v from transcripts t join videos v on v.id = t.video_id where "
                                + IS_TRANSLATION + " and t.created_at >= :a and t.created_at < :b group by 1",
                        p),
                labelled(
                        shares(
                                "select t.language k, t.language l, count(*) c" + live
                                        + " group by t.language order by c desc",
                                p),
                        names),
                ordered(
                        shares("select t.source k, '' l, count(*) c" + live + " group by t.source", p),
                        new String[][] {{"AUTO", "Automatic"}, {"MANUAL", "Manual"}, {"IMPORTED", "Imported"}}),
                jdbc.query(
                        "select v.language, t.language, count(*) c" + live
                                + " group by v.language, t.language order by c desc limit " + TOP,
                        p.params(),
                        (rs, i) -> new LanguagePair(rs.getString(1), rs.getString(2), rs.getLong(3))),
                coverage);
    }

    // ── Languages ─────────────────────────────────────────────────────────

    public LanguageAnalytics languages(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        record Catalog(String code, String name, boolean enabled, boolean isDefault) {}
        Map<String, Catalog> catalog = new LinkedHashMap<>();
        jdbc.query("select code, name, enabled, is_default from languages order by name", p.params(), rs -> {
            catalog.put(
                    rs.getString(1).toLowerCase(Locale.ROOT),
                    new Catalog(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)));
        });
        String liveT = " from transcripts t join videos v on v.id = t.video_id where v.deleted_at is null";
        Map<String, Long> videos = keyedCounts(
                "select lower(language) k, count(*) c from videos where deleted_at is null and language is not null group by 1",
                p);
        Map<String, Long> transcripts =
                keyedCounts("select lower(t.language) k, count(*) c" + liveT + " group by 1", p);
        Map<String, Long> into = keyedCounts(
                "select lower(t.language) k, count(*) c" + liveT + " and " + IS_TRANSLATION + " group by 1", p);
        String liveS = " from subtitles s join videos v on v.id = s.video_id where v.deleted_at is null";
        Map<String, Long> subtitles = keyedCounts("select lower(s.language) k, count(*) c" + liveS + " group by 1", p);
        Map<String, Long> published =
                keyedCounts("select lower(s.language) k, count(*) c" + liveS + " and s.published group by 1", p);
        Map<String, Long> views = keyedCounts("""
                select lower(v.language) k, count(*) c from video_views vv join videos v on v.id = vv.video_id
                where v.language is not null and vv.viewed_at >= :a and vv.viewed_at < :b group by 1""", p);
        Map<String, Long> watch = keyedCounts("""
                select lower(v.language) k, coalesce(sum(vv.watched_seconds), 0) c from video_views vv join videos v on v.id = vv.video_id
                where v.language is not null and vv.viewed_at >= :a and vv.viewed_at < :b group by 1""", p);
        Map<String, Long> ai = keyedCounts(
                "select lower(output_language) k, count(*) c from ai_generations where output_language is not null group by 1",
                p);

        Set<String> codes = new TreeSet<>(catalog.keySet());
        for (Map<String, Long> m : List.of(videos, transcripts, subtitles, views, ai)) {
            codes.addAll(m.keySet());
        }
        List<LanguageRow> rows = new ArrayList<>();
        for (String code : codes) {
            Catalog c = catalog.get(code);
            rows.add(new LanguageRow(
                    c != null ? c.code() : code,
                    c != null ? c.name() : code.toUpperCase(Locale.ROOT),
                    c != null,
                    c != null && c.enabled(),
                    c != null && c.isDefault(),
                    videos.getOrDefault(code, 0L),
                    transcripts.getOrDefault(code, 0L),
                    into.getOrDefault(code, 0L),
                    subtitles.getOrDefault(code, 0L),
                    published.getOrDefault(code, 0L),
                    views.getOrDefault(code, 0L),
                    watch.getOrDefault(code, 0L),
                    ai.getOrDefault(code, 0L)));
        }
        // Busiest first; unused catalog languages last, alphabetically.
        rows.sort(Comparator.comparingLong(LanguageRow::views)
                .reversed()
                .thenComparing(Comparator.comparingLong(LanguageRow::videos).reversed())
                .thenComparing(
                        Comparator.comparingLong(LanguageRow::transcripts).reversed())
                .thenComparing(LanguageRow::name));
        long inUse = rows.stream()
                .filter(r -> r.videos() + r.transcripts() + r.subtitleTracks() > 0)
                .count();
        return new LanguageAnalytics(
                p.range(),
                catalog.size(),
                catalog.values().stream().filter(Catalog::enabled).count(),
                inUse,
                rows.stream().filter(r -> !r.inCatalog()).count(),
                rows);
    }

    // ── Storage ───────────────────────────────────────────────────────────

    public StorageAnalytics storage(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        // Soft-deleted videos keep their file in the bucket, so they still count.
        String stored = " from videos where storage_key is not null";
        List<DayValue> uploaded = daily(
                "select cast(created_at as date) d, coalesce(sum(file_size), 0) v" + stored
                        + " and created_at >= :a and created_at < :b group by 1",
                p);
        double running = number("select coalesce(sum(file_size), 0)" + stored + " and created_at < :a", p);
        List<DayValue> cumulative = new ArrayList<>();
        for (DayValue d : uploaded) {
            running += d.value();
            cumulative.add(new DayValue(d.date(), running));
        }
        Map<String, String> names = languageNames();
        return new StorageAnalytics(
                p.range(),
                count("select coalesce(sum(file_size), 0)" + stored, p),
                count("select count(*)" + stored, p),
                count("select count(*) from videos where storage_key is null", p),
                count("select count(*)" + stored + " and file_size is null", p),
                count("select coalesce(sum(file_size), 0)" + stored + " and deleted_at is not null", p),
                count("select count(*)" + stored + " and deleted_at is not null", p),
                number("select coalesce(avg(file_size), 0)" + stored + " and file_size is not null", p),
                delta("select coalesce(sum(file_size), 0)" + stored + " and created_at >= :a and created_at < :b", p),
                uploaded,
                cumulative,
                amounts(
                        "select coalesce(mime_type, 'unknown') k, coalesce(mime_type, 'Unknown') l, coalesce(sum(file_size), 0) v, count(*) c"
                                + stored + " group by 1, 2 order by v desc",
                        p),
                amounts(
                                "select coalesce(language, '') k, coalesce(language, '') l, coalesce(sum(file_size), 0) v, count(*) c"
                                        + stored + " group by 1, 2 order by v desc",
                                p)
                        .stream()
                        .map(a -> new Amount(a.key(), languageLabel(a.key(), names), a.value(), a.count()))
                        .toList(),
                amounts("""
                        select coalesce(cast(u.id as varchar), 'none') k, coalesce(u.username, 'Unknown owner') l, coalesce(sum(v.file_size), 0) v, count(*) c
                        from videos v left join users u on u.id = v.owner_id where v.storage_key is not null
                        group by 1, 2 order by v desc limit %d""".formatted(TOP), p),
                jdbc.query(
                        "select id, title, file_size, mime_type, deleted_at is not null" + stored
                                + " and file_size is not null order by file_size desc limit " + TOP,
                        p.params(),
                        (rs, i) -> new LargeFile(
                                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getBoolean(5))),
                count("select count(*) from transcript_segments", p),
                count("select count(*) from subtitle_cues", p),
                settings.storage().storageQuotaGb() == null
                        ? null
                        : Math.round(settings.storage().storageQuotaGb() * 1024 * 1024 * 1024));
    }

    // ── AI ────────────────────────────────────────────────────────────────

    public AiAnalytics ai(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        String usage = " from ai_usage where created_at >= :a and created_at < :b";
        long requests = count("select count(*)" + usage, p);
        long success = count("select count(*)" + usage + " and status = 'SUCCESS'", p);
        Map<String, String> names = languageNames();
        return new AiAnalytics(
                p.range(),
                delta("select count(*)" + usage, p),
                delta("select coalesce(sum(cost_usd), 0)" + usage, p),
                requests == 0 ? null : (double) success / requests,
                delta("select count(*) from ai_generations where created_at >= :a and created_at < :b", p),
                delta("select count(*) from ai_chats where created_at >= :a and created_at < :b", p),
                count("select count(*) from ai_chat_messages where created_at >= :a and created_at < :b", p),
                number(
                        "select coalesce(avg(message_count), 0) from ai_chats where created_at >= :a and created_at < :b and message_count > 0",
                        p),
                count("select count(distinct video_id)" + usage + " and video_id is not null", p),
                daily("select cast(created_at as date) d, count(*) v" + usage + " group by 1", p),
                daily("select cast(created_at as date) d, coalesce(sum(cost_usd), 0) v" + usage + " group by 1", p),
                jdbc.query(
                        """
                        select feature, count(*),
                               sum(case when status = 'SUCCESS' then 1 else 0 end), sum(case when status = 'REFUSED' then 1 else 0 end),
                               sum(case when status = 'TRUNCATED' then 1 else 0 end), sum(case when status = 'ERROR' then 1 else 0 end),
                               avg(latency_ms), coalesce(avg(input_tokens + output_tokens + cache_write_tokens + cache_read_tokens), 0),
                               coalesce(sum(cost_usd), 0)
                        """ + usage + " group by feature order by 9 desc, 2 desc",
                        p.params(),
                        (rs, i) -> new AiFeatureRow(
                                rs.getString(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                rs.getLong(5),
                                rs.getLong(6),
                                nullableDouble(rs, 7),
                                rs.getDouble(8),
                                rs.getBigDecimal(9))),
                shares(
                        "select type k, type l, count(*) c from ai_generations where created_at >= :a and created_at < :b group by type order by c desc",
                        p),
                labelled(
                        shares(
                                "select output_language k, output_language l, count(*) c from ai_generations where created_at >= :a and created_at < :b group by 1 order by c desc",
                                p),
                        names),
                jdbc.query(
                        """
                        select u.video_id, v.title, count(*) req, coalesce(sum(u.cost_usd), 0) cost,
                               (select count(*) from ai_generations g where g.video_id = u.video_id and g.created_at >= :a and g.created_at < :b),
                               (select count(*) from ai_chats c where c.video_id = u.video_id and c.created_at >= :a and c.created_at < :b)
                        from ai_usage u left join videos v on v.id = u.video_id
                        where u.created_at >= :a and u.created_at < :b and u.video_id is not null
                        group by u.video_id, v.title order by cost desc, req desc limit %d""".formatted(TOP),
                        p.params(),
                        (rs, i) -> new AiVideoRow(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getLong(5),
                                rs.getLong(6),
                                rs.getBigDecimal(4))));
    }

    // ── query helpers ─────────────────────────────────────────────────────

    private long count(String sql, Period p) {
        Number n = jdbc.queryForObject(sql, p.params(), Number.class);
        return n == null ? 0 : n.longValue();
    }

    private double number(String sql, Period p) {
        Number n = jdbc.queryForObject(sql, p.params(), Number.class);
        return n == null ? 0 : n.doubleValue();
    }

    private Double nullableNumber(String sql, Period p) {
        Number n = jdbc.queryForObject(sql, p.params(), Number.class);
        return n == null ? null : n.doubleValue();
    }

    /** Runs {@code sql} for the period (:a/:b) and again for the previous one. */
    private Delta delta(String sql, Period p) {
        MapSqlParameterSource prev = new MapSqlParameterSource()
                .addValue("a", p.params().getValue("pa"))
                .addValue("b", p.params().getValue("pb"));
        Number current = jdbc.queryForObject(sql, p.params(), Number.class);
        Number previous = jdbc.queryForObject(sql, prev, Number.class);
        return new Delta(current == null ? 0 : current.doubleValue(), previous == null ? 0 : previous.doubleValue());
    }

    /** {@code sql} must return (d date, v number); missing days are filled with 0. */
    private List<DayValue> daily(String sql, Period p) {
        Map<LocalDate, Double> byDay = new HashMap<>();
        jdbc.query(sql, p.params(), rs -> {
            Date d = rs.getDate(1);
            byDay.put(d.toLocalDate(), rs.getDouble(2));
        });
        List<DayValue> out = new ArrayList<>(p.range().days());
        for (LocalDate d = p.range().from(); !d.isAfter(p.range().to()); d = d.plusDays(1)) {
            out.add(new DayValue(d, byDay.getOrDefault(d, 0.0)));
        }
        return out;
    }

    /** {@code sql} must return (k, l, count). */
    private List<Share> shares(String sql, Period p) {
        return jdbc.query(sql, p.params(), (rs, i) -> new Share(rs.getString(1), rs.getString(2), rs.getLong(3)));
    }

    /** {@code sql} must return (k, l, value, count). */
    private List<Amount> amounts(String sql, Period p) {
        return jdbc.query(
                sql,
                p.params(),
                (rs, i) -> new Amount(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getLong(4)));
    }

    /** {@code sql} must return (k, c). */
    private Map<String, Long> keyedCounts(String sql, Period p) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query(sql, p.params(), rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    /** Fixed bucket order and labels; buckets with no rows are dropped. */
    private static List<Share> ordered(List<Share> rows, String[][] keyLabels) {
        Map<String, Long> counts = new HashMap<>();
        rows.forEach(r -> counts.put(r.key(), r.count()));
        List<Share> out = new ArrayList<>();
        for (String[] kl : keyLabels) {
            Long c = counts.remove(kl[0]);
            if (c != null) {
                out.add(new Share(kl[0], kl[1], c));
            }
        }
        counts.forEach((k, c) -> out.add(new Share(k, k, c)));
        return out;
    }

    private Map<String, String> languageNames() {
        Map<String, String> names = new HashMap<>();
        jdbc.query("select code, name from languages", Map.of(), rs -> {
            names.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2));
        });
        return names;
    }

    private static List<Share> labelled(List<Share> rows, Map<String, String> names) {
        return rows.stream()
                .map(s -> new Share(s.key(), languageLabel(s.key(), names), s.count()))
                .toList();
    }

    private static String languageLabel(String code, Map<String, String> names) {
        if (code == null || code.isBlank()) {
            return "Not set";
        }
        return names.getOrDefault(code.toLowerCase(Locale.ROOT), code.toUpperCase(Locale.ROOT));
    }

    private static Double nullableDouble(ResultSet rs, int column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
