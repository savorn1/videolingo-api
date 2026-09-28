package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
