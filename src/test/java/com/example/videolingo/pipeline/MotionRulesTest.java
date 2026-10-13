package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

// Reverse, stabilise, zoom to an area and green screen on a trim: the checks and the filters.
class MotionRulesTest {

    @Test
    void onlyShortClipsPlayBackwards() {
        assertNull(VideoEditRules.validateReverse(true, 60_000L));
        assertNotNull(VideoEditRules.validateReverse(true, 61_000L));
        assertNull(VideoEditRules.validateReverse(false, 600_000L));
        assertNull(VideoEditRules.validateReverse(null, null));
    }

    @Test
    void theZoomAreaIsInsideThePictureAndNotTooSmall() {
        assertNull(VideoEditRules.validateZoomTo(new VideoEditRules.ZoomTo(0.5, 0.5, 0.5)));
        assertNotNull(VideoEditRules.validateZoomTo(new VideoEditRules.ZoomTo(1.2, 0.5, 0.5)));
        assertNotNull(VideoEditRules.validateZoomTo(new VideoEditRules.ZoomTo(0.5, 0.5, 0.1)));
        assertNotNull(VideoEditRules.validateZoomTo(new VideoEditRules.ZoomTo(0.5, 0.5, 1)));
    }

    @Test
    void theGreenScreenNeedsColoursAndASensibleTolerance() {
        assertNull(VideoEditRules.validateGreenScreen(new VideoEditRules.GreenScreen(null, 0.3, "#000000")));
        assertNotNull(VideoEditRules.validateGreenScreen(new VideoEditRules.GreenScreen("green", 0.3, "#000000")));
        assertNotNull(VideoEditRules.validateGreenScreen(new VideoEditRules.GreenScreen(null, 0.9, "#000000")));
        assertNotNull(VideoEditRules.validateGreenScreen(new VideoEditRules.GreenScreen(null, 0.3, null)));
    }

    @Test
    void theZoomScalesUpFrameByFrameAndCropsAroundTheChosenCentre() {
        List<String> f = MediaTools.zoomToFilters(new VideoEditRules.ZoomTo(0.25, 0.75, 0.5), 4000, 640, 360);
        assertEquals("scale=w='trunc(iw*(1+1.0000*min(1,max(0,t/4.000)))/2)*2':h=-2:eval=frame", f.get(0));
        // Aimed from t (crop's own iw stays at the first frame's width while the picture grows).
        assertTrue(f.get(1).startsWith("crop=640:360:x='max(0,min(trunc(640*"), f.get(1));
        assertTrue(f.get(1).contains(",0.2500*trunc(640*"), f.get(1));
        assertTrue(f.get(1).contains(",0.7500*trunc(360*"), f.get(1));
        assertTrue(!f.get(1).contains("iw") && !f.get(1).contains("ih"), f.get(1));
    }

    @Test
    void theGreenScreenLaysTheKeyedPictureOverTheColour() {
        String g = MediaTools.greenScreenGraph(new VideoEditRules.GreenScreen(null, 0.3, "#102030"), 320, 180);
        assertTrue(g.startsWith("color=c=0x102030:s=320x180[bg];[0:v]chromakey=0x00FF00:0.300:0.08[fg]"), g);
        assertTrue(g.endsWith("overlay=shortest=1:format=auto,format=yuv420p[v]"), g);
    }

    @Test
    void theSummarySaysWhatWasDone() {
        assertEquals(
                "played backwards, stabilised, green screen",
                VideoEditRules.describeMotion(true, true, new VideoEditRules.GreenScreen(null, 0.3, "#000000")));
        assertNull(VideoEditRules.describeMotion(null, false, null));
        assertEquals(
                "zoom into 50% of the picture",
                VideoEditRules.describeZoomTo(new VideoEditRules.ZoomTo(0.5, 0.5, 0.5)));
    }
}
