package com.example.videolingo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// Response shapes for /api/admin/analytics/*. Every period metric comes with
// the same-length period just before it (Delta), so the UI can show a trend.
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    // ── shared pieces ─────────────────────────────────────────────────────

    /** [from, to] inclusive, and the equally long period right before it. */
    public record Range(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, int days) {
    }

    public record Delta(double current, double previous) {
    }

    /** One day of a zero-filled daily series. */
    public record DayValue(LocalDate date, double value) {
    }

    /** A breakdown bucket: count of things with this key. */
    public record Share(String key, String label, long count) {
    }

    /** A breakdown bucket with a measured amount (bytes, seconds, USD…) and a count. */
    public record Amount(String key, String label, double value, long count) {
    }

    // ── users ─────────────────────────────────────────────────────────────

    public record TopViewer(Long userId, String username, long views, long watchSeconds, long completed) {
    }

    public record UserAnalytics(Range range, long totalUsers, long enabledUsers, long disabledUsers, long admins, long neverSignedIn,
                                Delta newUsers,
                                // Distinct signed-in accounts that watched something.
                                Delta activeViewers,
                                // Accounts whose most recent sign-in falls in the period.
                                long signedInDuringPeriod,
                                List<DayValue> newUsersDaily, List<DayValue> activeViewersDaily,
                                List<Share> byRole, List<Share> byCustomRole, List<TopViewer> topViewers) {
    }

    // ── videos ────────────────────────────────────────────────────────────

    public record TopVideo(Long videoId, String title, long views, long uniqueViewers, long watchSeconds,
                           // 0–1
                           double completionRate) {
    }

    public record VideoAnalytics(Range range, long totalVideos, long enabledVideos, long disabledVideos, long trashedVideos,
                                 Delta uploads, long totalDurationSeconds, double averageDurationSeconds,
                                 long withoutTranscript, long withoutSubtitles, long withoutCategory, long neverWatched,
                                 List<DayValue> uploadsDaily, List<Share> byLanguage, List<Share> byCategory,
                                 List<Share> byDuration, List<TopVideo> topVideos) {
    }

    // ── watch ─────────────────────────────────────────────────────────────

    public record WatchAnalytics(Range range, Delta views, Delta watchSeconds, Delta uniqueViewers,
                                 // Share of views that reached the end (0–1), this period vs previous.
                                 Delta completionRate,
                                 long anonymousViews, double averageWatchSeconds,
                                 // Mean of watched ÷ duration across views of videos with a known duration (0–1); null if none.
                                 Double averagePercentWatched,
                                 List<DayValue> viewsDaily, List<DayValue> watchSecondsDaily,
                                 // 24 buckets "0"…"23" (server time) / 7 buckets Mon…Sun.
                                 List<Share> byHour, List<Share> byWeekday,
                                 // value = watch seconds, count = views.
                                 List<Amount> byLanguage, List<TopVideo> topVideos) {
    }

    // ── translations ──────────────────────────────────────────────────────

    public record LanguagePair(String from, String to, long count) {
    }

    public record TranslationAnalytics(Range range,
                                       // Transcripts in a language other than the video's spoken language.
                                       long translations, Delta newTranslations, long videosTranslated, long videosWithTranscript,
                                       long liveVideos, double averageTranslationsPerVideo, long translatedWords,
                                       long translatedSubtitleTracks,
                                       // TRANSLATE jobs created in the period, by status.
                                       List<Share> jobsByStatus, Double jobSuccessRate, Double averageJobSeconds,
                                       List<DayValue> translationsDaily, List<Share> byTargetLanguage, List<Share> bySource,
                                       List<LanguagePair> topPairs,
                                       // Live videos by how many translations they have: "0", "1", "2", "3+".
                                       List<Share> coverage) {
    }

    // ── languages ─────────────────────────────────────────────────────────

    public record LanguageRow(String code, String name, boolean inCatalog, boolean enabled, boolean isDefault,
                              long videos, long transcripts, long translationsInto, long subtitleTracks, long publishedSubtitles,
                              // In the period, for videos spoken in this language.
                              long views, long watchSeconds,
                              long aiGenerations) {
    }

    public record LanguageAnalytics(Range range, long catalogLanguages, long enabledLanguages, long languagesInUse,
                                    // Codes used by content but missing from the catalog.
                                    long unknownCodes, List<LanguageRow> languages) {
    }

    // ── storage ───────────────────────────────────────────────────────────

    public record LargeFile(Long videoId, String title, long bytes, String mimeType, boolean trashed) {
    }

    public record StorageAnalytics(Range range,
                                   // Videos whose file lives in our bucket (storageKey set), incl. trashed.
                                   long storedBytes, long storedVideos, long externalVideos, long unknownSizeVideos,
                                   // Held by trashed videos — freed if they were purged.
                                   long trashedBytes, long trashedVideos, double averageFileBytes,
                                   Delta uploadedBytes, List<DayValue> uploadedBytesDaily,
                                   // Stored bytes at the end of each day (from upload dates).
                                   List<DayValue> storedBytesDaily,
                                   List<Amount> byMimeType, List<Amount> byLanguage, List<Amount> byOwner, List<LargeFile> largest,
                                   long transcriptSegments, long subtitleCues,
                                   // Settings › Storage quota in bytes; null = none set.
                                   Long quotaBytes) {
    }

    // ── AI ────────────────────────────────────────────────────────────────

    public record AiFeatureRow(String feature, long requests, long succeeded, long refused, long truncated, long errors,
                               Double averageLatencyMs, double averageTokens, BigDecimal costUsd) {
    }

    public record AiVideoRow(Long videoId, String title, long requests, long generations, long chats, BigDecimal costUsd) {
    }

    public record AiAnalytics(Range range, Delta requests, Delta costUsd, Double successRate, Delta generations, Delta chats,
                              long chatMessages, double averageMessagesPerChat, long videosWithAi,
                              List<DayValue> requestsDaily, List<DayValue> costDaily, List<AiFeatureRow> byFeature,
                              List<Share> generationsByType, List<Share> generationsByLanguage, List<AiVideoRow> topVideos) {
    }
}
