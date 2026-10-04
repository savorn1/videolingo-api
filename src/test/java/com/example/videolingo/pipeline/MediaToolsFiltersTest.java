package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MediaToolsFiltersTest {

    private static final MediaTools.CropRect CROP = new MediaTools.CropRect(10, 20, 640, 360);
    private static final MediaTools.ScaleSize SCALE = new MediaTools.ScaleSize(1080, 1920);

    @Test
    void nothingAskedForMeansNoFilters() {
        assertTrue(MediaTools.videoFilters(null, null, false, false, null).isEmpty());
        assertTrue(MediaTools.videoFilters(null, 0, false, false, null).isEmpty());
    }

    @Test
    void quarterTurnsAreClockwiseTransposes() {
        assertEquals(List.of("transpose=1"), MediaTools.videoFilters(null, 90, false, false, null));
        assertEquals(List.of("hflip,vflip"), MediaTools.videoFilters(null, 180, false, false, null));
        assertEquals(List.of("transpose=2"), MediaTools.videoFilters(null, 270, false, false, null));
    }

    @Test
    void flipsComeAfterTheTurn() {
        assertEquals(List.of("transpose=1", "hflip", "vflip"), MediaTools.videoFilters(null, 90, true, true, null));
        assertEquals(List.of("vflip"), MediaTools.videoFilters(null, null, false, true, null));
    }

    @Test
    void cropThenTurnAndFlipThenResize() {
        assertEquals(
                List.of("crop=640:360:10:20", "transpose=1", "hflip", "scale=1080:1920"),
                MediaTools.videoFilters(CROP, 90, true, false, SCALE));
    }

    @Test
    void aPlainLookAddsNoFilters() {
        assertTrue(MediaTools.lookFilters(null).isEmpty());
        assertTrue(MediaTools.lookFilters(new VideoEditRules.Look(0, 1, 1, 0, false, false, false))
                .isEmpty());
    }

    @Test
    void aLookIsAdjustedThenTintedThenBlurredThenVignetted() {
        assertEquals(
                List.of("eq=brightness=0.100:contrast=1.200:saturation=0.000", "gblur=sigma=2.00", "vignette=PI/4"),
                MediaTools.lookFilters(new VideoEditRules.Look(0.1, 1.2, 1.5, 2, true, true, true)));
        assertEquals(
                2,
                MediaTools.lookFilters(new VideoEditRules.Look(0, 1, 1, 0, false, true, false))
                                .size()
                        + 1);
    }
}
