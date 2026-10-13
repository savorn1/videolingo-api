package com.example.videolingo.service;

import java.time.Duration;
import java.time.LocalDateTime;

// The rules for scheduling a video to be enabled by itself.
public final class PublishSchedule {

    /** As far ahead as a video can be scheduled. */
    public static final Duration MAX_AHEAD = Duration.ofDays(366);

    /** A time this close behind now still counts (the form's minute, a slow request). */
    static final Duration GRACE = Duration.ofMinutes(2);

    private PublishSchedule() {}

    /** Why `publishAt` can't be used for a video, or null when it can. */
    public static String validate(boolean enabled, LocalDateTime publishAt, LocalDateTime now) {
        if (enabled) {
            return "The video is already enabled — disable it first to schedule it";
        }
        if (publishAt.isBefore(now.minus(GRACE))) {
            return "Choose a time in the future";
        }
        if (publishAt.isAfter(now.plus(MAX_AHEAD))) {
            return "A video can be scheduled at most a year ahead";
        }
        return null;
    }
}
