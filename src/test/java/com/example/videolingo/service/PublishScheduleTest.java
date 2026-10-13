package com.example.videolingo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PublishScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Test
    void aDisabledVideoCanBeScheduledAheadUpToAYear() {
        assertNull(PublishSchedule.validate(false, NOW.plusHours(1), NOW));
        assertNull(PublishSchedule.validate(false, NOW.plusDays(365), NOW));
    }

    @Test
    void aMomentAgoStillCounts() {
        assertNull(PublishSchedule.validate(false, NOW.minusMinutes(1), NOW));
        assertEquals("Choose a time in the future", PublishSchedule.validate(false, NOW.minusMinutes(5), NOW));
    }

    @Test
    void notAnEnabledVideoNorTooFarAhead() {
        assertEquals(
                "The video is already enabled — disable it first to schedule it",
                PublishSchedule.validate(true, NOW.plusHours(1), NOW));
        assertEquals(
                "A video can be scheduled at most a year ahead",
                PublishSchedule.validate(false, NOW.plusDays(400), NOW));
    }
}
