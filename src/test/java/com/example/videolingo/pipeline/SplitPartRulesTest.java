package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A split part's own name and shape (VideoEditRules.validateSplitPart / splitCrop).
class SplitPartRulesTest {

    @Test
    void aNameAndShapeAreOptional() {
        assertNull(VideoEditRules.validateSplitPart(null, null, null, null));
        assertNull(VideoEditRules.validateSplitPart("Greetings", 0.5625, 1920, 1080));
    }

    @Test
    void aLongNameOrAStrangeShapeIsRefused() {
        assertTrue(VideoEditRules.validateSplitPart("x".repeat(81), null, 1920, 1080).contains("80"));
        assertTrue(VideoEditRules.validateSplitPart(null, 10.0, 1920, 1080).contains("shape"));
        assertTrue(VideoEditRules.validateSplitPart(null, 1.0, null, 1080).contains("size"));
    }

    @Test
    void aShapeIsTheLargestCentredBoxWithEvenSides() {
        // 9:16 in 1920×1080: 608×1080 (607.5 rounded, then even), centred.
        assertEquals(new MediaTools.CropRect(656, 0, 608, 1080), VideoEditRules.splitCrop(9 / 16.0, 1920, 1080));
        assertEquals(new MediaTools.CropRect(420, 0, 1080, 1080), VideoEditRules.splitCrop(1.0, 1920, 1080));
    }

    @Test
    void theFramesOwnShapeOrNoSizeNeedsNoCrop() {
        assertNull(VideoEditRules.splitCrop(16 / 9.0, 1920, 1080));
        assertNull(VideoEditRules.splitCrop(1.0, null, 1080));
        assertNull(VideoEditRules.splitCrop(null, 1920, 1080));
    }
}
