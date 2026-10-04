package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoEditRulesTest {

    @Test
    void trimAcceptsAValidRange() {
        assertNull(VideoEditRules.validateTrim(1000, 5000L, 10000L));
        assertNull(VideoEditRules.validateTrim(1000, null, 10000L));
        assertNull(VideoEditRules.validateTrim(0, 100L, null));
    }

    @Test
    void trimRejectsOutOfBoundsOrBackwardsRanges() {
        assertNotNull(VideoEditRules.validateTrim(-1, 100L, null));
        assertNotNull(VideoEditRules.validateTrim(500, 500L, null));
        assertNotNull(VideoEditRules.validateTrim(500, 400L, null));
        assertNotNull(VideoEditRules.validateTrim(10000, 12000L, 10000L));
        assertNotNull(VideoEditRules.validateTrim(1000, 20000L, 10000L));
    }

    @Test
    void cropMustFitInsideTheVideo() {
        assertNull(VideoEditRules.validateCrop(0, 0, 640, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(0, 0, 0, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(-1, 0, 640, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(1000, 0, 640, 480, 1280, 720));
        assertNotNull(VideoEditRules.validateCrop(0, 500, 640, 480, 1280, 720));
        // Unknown video dimensions: only positivity is checked.
        assertNull(VideoEditRules.validateCrop(0, 0, 640, 480, null, null));
    }

    @Test
    void scaleMustBePositive() {
        assertNull(VideoEditRules.validateScale(640, 480));
        assertNotNull(VideoEditRules.validateScale(0, 480));
        assertNotNull(VideoEditRules.validateScale(640, -1));
    }

    @Test
    void segmentsNeedAtLeastOneAndAtMostTheCap() {
        assertNotNull(VideoEditRules.validateSegments(List.of(), null));
        assertNull(VideoEditRules.validateSegments(List.of(new VideoEditRules.Segment(0, 1000L)), 10000L));
        List<VideoEditRules.Segment> tooMany = java.util.Collections.nCopies(VideoEditRules.MAX_SEGMENTS + 1,
                new VideoEditRules.Segment(0, 100L));
        assertNotNull(VideoEditRules.validateSegments(tooMany, null));
    }

    @Test
    void segmentsValidateEachRangeIndividually() {
        var segments = List.of(new VideoEditRules.Segment(0, 1000L), new VideoEditRules.Segment(900, 500L));
        String error = VideoEditRules.validateSegments(segments, null);
        assertNotNull(error);
        assertTrue(error.contains("Segment 2"));
    }

    @Test
    void rotationIsAQuarterTurn() {
        assertNull(VideoEditRules.validateRotation(null));
        for (int d : new int[]{0, 90, 180, 270}) {
            assertNull(VideoEditRules.validateRotation(d));
        }
        assertNotNull(VideoEditRules.validateRotation(45));
        assertNotNull(VideoEditRules.validateRotation(-90));
        assertNotNull(VideoEditRules.validateRotation(360));
    }

    @Test
    void orientationIsOnlyAskedForWhenSomethingChanges() {
        assertFalse(VideoEditRules.hasOrientation(null, null, null));
        assertFalse(VideoEditRules.hasOrientation(0, false, false));
        assertTrue(VideoEditRules.hasOrientation(90, null, null));
        assertTrue(VideoEditRules.hasOrientation(null, true, null));
        assertTrue(VideoEditRules.hasOrientation(0, false, true));
        assertNull(VideoEditRules.describeOrientation(0, false, false));
    }

    @Test
    void quarterTurnsSwapTheSides() {
        assertTrue(VideoEditRules.swapsSides(90));
        assertTrue(VideoEditRules.swapsSides(270));
        assertFalse(VideoEditRules.swapsSides(180));
        assertFalse(VideoEditRules.swapsSides(0));
        assertFalse(VideoEditRules.swapsSides(null));
    }

    @Test
    void orientationIsDescribedInWords() {
        assertEquals("Turned 90° clockwise", VideoEditRules.describeOrientation(90, false, false));
        assertEquals("Flipped left–right", VideoEditRules.describeOrientation(null, true, false));
        assertEquals("Turned 180° clockwise, flipped left–right, flipped top–bottom", VideoEditRules.describeOrientation(180, true, true));
    }

    @Test
    void anExtendedTrimMayRunPastTheEndButNotFar() {
        assertNull(VideoEditRules.validateExtendedTrim(1000, 70000L, 60000L));
        assertNull(VideoEditRules.validateExtendedTrim(1000, 30000L, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(1000, null, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(1000, 70000L, null));
        assertNotNull(VideoEditRules.validateExtendedTrim(60000, 70000L, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(5000, 5000L, 60000L));
        assertNotNull(VideoEditRules.validateExtendedTrim(0, 60000L + VideoEditRules.MAX_EXTEND_MS + 1, 60000L));
    }

    @Test
    void padFilterHoldsTheLastFrame() {
        assertEquals("tpad=stop_mode=clone:stop_duration=11.000", MediaTools.padFilter(10000));
    }

    @Test
    void aLookIsCheckedAndDescribed() {
        VideoEditRules.Look plain = new VideoEditRules.Look(0, 1, 1, 0, false, false, false);
        assertTrue(plain.isPlain());
        assertNull(VideoEditRules.describeLook(plain));
        assertNull(VideoEditRules.validateLook(null));
        assertNull(VideoEditRules.validateLook(new VideoEditRules.Look(0.2, 1.4, 1.5, 3, false, true, true)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(1.5, 1, 1, 0, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(0, 3, 1, 0, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(0, 1, 5, 0, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(0, 1, 1, 50, false, false, false)));
        assertNotNull(VideoEditRules.validateLook(new VideoEditRules.Look(Double.NaN, 1, 1, 0, false, false, false)));
        assertEquals("Brighter, black & white, vignette", VideoEditRules.describeLook(new VideoEditRules.Look(0.1, 1, 1, 0, true, false, true)));
    }
}
