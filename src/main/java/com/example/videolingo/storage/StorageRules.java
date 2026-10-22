package com.example.videolingo.storage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// The storage report's arithmetic (how much each video takes, the biggest first) and which old versions a clean-up
// removes. Pure, so it's unit-tested; StorageService reads the numbers and deletes the files.
public final class StorageRules {

    private StorageRules() {}

    /** One video's files: its own, its kept versions, its waiting edit results, its voice-overs and its exports. */
    public record Row(
            long videoId,
            String title,
            boolean deleted,
            long fileBytes,
            int versions,
            long versionBytes,
            int results,
            long resultBytes,
            long dubBytes,
            long exportBytes) {

        public long totalBytes() {
            return fileBytes + versionBytes + resultBytes + dubBytes + exportBytes;
        }
    }

    public record Totals(long files, long versions, long results, long dubs, long exports) {
        public long all() {
            return files + versions + results + dubs + exports;
        }
    }

    public static Totals totals(List<Row> rows) {
        return new Totals(
                rows.stream().mapToLong(Row::fileBytes).sum(),
                rows.stream().mapToLong(Row::versionBytes).sum(),
                rows.stream().mapToLong(Row::resultBytes).sum(),
                rows.stream().mapToLong(Row::dubBytes).sum(),
                rows.stream().mapToLong(Row::exportBytes).sum());
    }

    /** The `n` videos taking the most space, biggest first (ties by id); empty ones left out. */
    public static List<Row> biggest(List<Row> rows, int n) {
        return rows.stream()
                .filter(r -> r.totalBytes() > 0)
                .sorted(Comparator.comparingLong(Row::totalBytes).reversed().thenComparingLong(Row::videoId))
                .limit(n)
                .toList();
    }

    /** A kept version: its id, when it was made and its size. */
    public record Version(long id, LocalDateTime createdAt, long bytes) {}

    public static final int MAX_KEEP = 20;
    public static final int MAX_AGE_DAYS = 3650;

    /**
     * The versions of one video a clean-up removes: all but the newest `keepLatest`, and of those only the ones made
     * before `cutoff` (a version with no date counts as old).
     */
    public static List<Version> toPrune(List<Version> versions, int keepLatest, LocalDateTime cutoff) {
        List<Version> newestFirst = new ArrayList<>(versions);
        newestFirst.sort(Comparator.comparing(
                        Version::createdAt, Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                .thenComparingLong(Version::id)
                .reversed());
        List<Version> out = new ArrayList<>();
        for (int i = keepLatest; i < newestFirst.size(); i++) {
            Version v = newestFirst.get(i);
            if (v.createdAt() == null || v.createdAt().isBefore(cutoff)) {
                out.add(v);
            }
        }
        return out;
    }

    /** Null when the clean-up's settings are usable, else why not. */
    public static String validatePrune(int keepLatest, int olderThanDays) {
        if (keepLatest < 0 || keepLatest > MAX_KEEP) {
            return "Keep between 0 and " + MAX_KEEP + " versions of each video";
        }
        if (olderThanDays < 0 || olderThanDays > MAX_AGE_DAYS) {
            return "The age is between 0 and " + MAX_AGE_DAYS + " days";
        }
        return null;
    }
}
