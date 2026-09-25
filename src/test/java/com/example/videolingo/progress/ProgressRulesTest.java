package com.example.videolingo.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressRulesTest {

    @Test
    void completesAtTheEndOrNearlyThere() {
        assertTrue(ProgressRules.completes(true, 3, 600.0));
        assertTrue(ProgressRules.completes(false, 570, 600.0));
        assertFalse(ProgressRules.completes(false, 500, 600.0));
        assertFalse(ProgressRules.completes(false, 500, null));
    }

    @Test
    void capsWatchTimeToRealElapsedTime() {
        assertEquals(15, ProgressRules.acceptedDelta(15.2, 16L));
        assertEquals(10, ProgressRules.acceptedDelta(600, 5L));
        assertEquals(120, ProgressRules.acceptedDelta(600, null));
        assertEquals(0, ProgressRules.acceptedDelta(-4, 10L));
        assertEquals(0, ProgressRules.acceptedDelta(Double.NaN, 10L));
    }

    @Test
    void reportsPercent() {
        assertEquals(50, ProgressRules.percent(30, 60, false));
        assertEquals(100, ProgressRules.percent(0, 60, true));
        assertNull(ProgressRules.percent(30, null, false));
    }
}
