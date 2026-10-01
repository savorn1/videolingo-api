package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
