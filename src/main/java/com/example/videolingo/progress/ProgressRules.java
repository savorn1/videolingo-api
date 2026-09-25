package com.example.videolingo.progress;

// How a player heartbeat turns into progress. Pure and static so it's unit-tested.
public final class ProgressRules {

    /** Watching this share of a video counts as finishing it (credits are often skipped). */
    static final double COMPLETE_AT = 0.95;
    /** A single heartbeat never adds more than this much watch time. */
    static final long MAX_DELTA_SECONDS = 120;
    /** Slack for clock differences between the browser and the server. */
    static final long DELTA_SLACK_SECONDS = 5;
    /** Below this, "Continue watching" doesn't bother listing a video. */
    public static final int MIN_RESUME_SECONDS = 5;

    private ProgressRules() {
    }

    public static boolean completes(boolean ended, double positionSeconds, Double durationSeconds) {
        if (ended) {
            return true;
        }
        return durationSeconds != null && durationSeconds > 0 && positionSeconds >= durationSeconds * COMPLETE_AT;
    }

    /**
     * Watch time a heartbeat may add: what the player claims, but never more
     * than the time that actually passed since the previous heartbeat (plus
     * slack), nor more than MAX_DELTA_SECONDS — so a tampered or buggy client
     * can't inflate statistics. `secondsSinceLast` is null for a first beat.
     */
    public static long acceptedDelta(double claimedSeconds, Long secondsSinceLast) {
        if (!(claimedSeconds > 0)) {
            return 0;
        }
        long claimed = Math.round(claimedSeconds);
        long cap = secondsSinceLast == null ? MAX_DELTA_SECONDS : Math.min(MAX_DELTA_SECONDS, Math.max(0, secondsSinceLast) + DELTA_SLACK_SECONDS);
        return Math.min(claimed, cap);
    }

    /** 0–100, or null when the length is unknown. */
    public static Integer percent(int positionSeconds, Integer durationSeconds, boolean completed) {
        if (completed) {
            return 100;
        }
        if (durationSeconds == null || durationSeconds <= 0) {
            return null;
        }
        return (int) Math.max(0, Math.min(100, Math.round(positionSeconds * 100.0 / durationSeconds)));
    }
}
