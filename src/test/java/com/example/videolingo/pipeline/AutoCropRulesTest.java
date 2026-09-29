package com.example.videolingo.pipeline;

import com.example.videolingo.pipeline.AutoCropRules.Centroid;
import com.example.videolingo.pipeline.AutoCropRules.CropRect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCropRulesTest {

    @Test
    void emptyHeatmapDefaultsToTheCentreWithNoConfidence() {
        Centroid c = AutoCropRules.centroid(new float[4 * 3], 4, 3);
        assertEquals(0.5, c.x());
        assertEquals(0.5, c.y());
        assertEquals(0, c.confidence());
    }

    @Test
    void weighsTowardTheBusyCell() {
        // 4x2 grid; the hot cell is column 3 (of 0..3), row 0 — right side, top half.
        float[] h = new float[4 * 2];
        h[3] = 1f;
        Centroid c = AutoCropRules.centroid(h, 4, 2);
        assertEquals(0.875, c.x(), 1e-9); // (3+0.5)/4
        assertEquals(0.25, c.y(), 1e-9); // (0+0.5)/2
        assertEquals(0.125, c.confidence(), 1e-9); // 1 / (4*2)
    }

    @Test
    void aUniformHeatmapCentresLikeAnEmptyOne() {
        float[] h = new float[4 * 4];
        java.util.Arrays.fill(h, 0.5f);
        Centroid c = AutoCropRules.centroid(h, 4, 4);
        assertEquals(0.5, c.x(), 1e-9);
        assertEquals(0.5, c.y(), 1e-9);
        assertEquals(0.5, c.confidence(), 1e-9);
    }

    @Test
    void fitsTheLargestBoxOfTheShapeCenteredOnTheCentroid() {
        // 1920x1080, 9:16 (vertical): width-limited, so h = videoHeight, w = h*aspect.
        CropRect r = AutoCropRules.suggestCrop(9.0 / 16, 1920, 1080, 0.5, 0.5);
        assertEquals(1080, r.h());
        assertEquals(608, r.w()); // 1080 * 9/16 rounded
        assertEquals((1920 - 608) / 2, r.x());
        assertEquals(0, r.y());
    }

    @Test
    void pullsBackInsideTheFrameWhenTheCentroidIsNearAnEdge() {
        // Same box, but centred hard on the left edge: x can't go negative.
        CropRect r = AutoCropRules.suggestCrop(9.0 / 16, 1920, 1080, 0.02, 0.5);
        assertEquals(0, r.x());
        assertTrue(r.x() + r.w() <= 1920);
    }

    @Test
    void wideAspectIsHeightLimited() {
        CropRect r = AutoCropRules.suggestCrop(16.0 / 9, 1080, 1920, 0.5, 0.2);
        assertEquals(1080, r.w());
        assertEquals(608, r.h());
        assertEquals(0, r.x());
        assertTrue(r.y() >= 0 && r.y() + r.h() <= 1920);
    }
}
