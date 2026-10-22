package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

// Punch-in zooms, the progress bar, colour grades and test renders (VideoEditRules).
class EditExtrasRulesTest {

    private static VideoEditRules.PunchIn punch(long... edges) {
        java.util.ArrayList<VideoEditRules.Segment> ranges = new java.util.ArrayList<>();
        for (int i = 0; i < edges.length; i += 2) {
            ranges.add(new VideoEditRules.Segment(edges[i], edges[i + 1]));
        }
        return new VideoEditRules.PunchIn(1.12, ranges);
    }

    @Test
    void punchInMomentsMustBeLongEnoughInOrderAndInTheClip() {
        assertNull(VideoEditRules.validatePunchIn(null, 10_000L));
        assertNull(VideoEditRules.validatePunchIn(punch(0, 2000, 4000, 6000), 10_000L));
        assertTrue(VideoEditRules.validatePunchIn(
                        new VideoEditRules.PunchIn(2, punch(0, 2000).ranges()), 10_000L)
                .contains("150%"));
        assertTrue(VideoEditRules.validatePunchIn(punch(0, 100), 10_000L).contains("0.3"));
        assertTrue(VideoEditRules.validatePunchIn(punch(0, 2000, 1000, 3000), 10_000L)
                .contains("overlap"));
        assertTrue(VideoEditRules.validatePunchIn(punch(9000, 12_000), 10_000L).contains("past the end"));
    }

    @Test
    void punchInZoomsOnlyInsideTheMoments() {
        assertEquals(
                "(1+0.1200*min(1,between(t,0.000,2.000)+between(t,4.000,6.500)))",
                VideoEditRules.punchFactor(punch(0, 2000, 4000, 6500)));
    }

    @Test
    void theProgressBarIsAColourAndAThinEvenStrip() {
        assertNull(VideoEditRules.validateProgressBar("#FFcc00"));
        assertTrue(VideoEditRules.validateProgressBar("yellow").contains("colour"));
        assertEquals(12, VideoEditRules.progressBarHeight(1080));
        assertEquals(4, VideoEditRules.progressBarHeight(180));
        String graph = VideoEditRules.progressBarGraph(1920, 1080, "#FFCC00", 5000, true);
        assertTrue(graph.startsWith("color=c=0xffcc00:s=1920x12:d=5.000[bar]"));
        assertTrue(graph.contains("overlay=x='-w+w*t/5.000'"));
        assertTrue(graph.endsWith(";[0:a]anull[a]"));
    }

    @Test
    void onlyOurOwnColourGradeUploadsAreTaken() {
        assertNull(VideoEditRules.validateLutKey(null));
        assertNull(VideoEditRules.validateLutKey("luts/0b0c0d0e-0000-4000-8000-000000000000.cube"));
        assertTrue(VideoEditRules.validateLutKey("../etc/passwd").contains("upload"));
        assertTrue(VideoEditRules.validateLutKey("luts/0b0c0d0e-0000-4000-8000-000000000000.png")
                .contains("upload"));
    }

    private static EditJobParams.Trim trim(long start, Long end, MediaTools.ScaleSize scale) {
        return new EditJobParams.Trim(
                "TRIM",
                start,
                end,
                null,
                scale,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new VideoEditRules.Fade(3000, 6000, "BLACK"),
                new VideoEditRules.Freeze(20_000, 1000),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new VideoEditRules.Freeze(2000, 1000), new VideoEditRules.Freeze(9000, 1000)),
                List.of(new VideoEditRules.SpeedRange(6000, 12_000, 2)),
                punch(1000, 3000, 7800, 9000),
                "#ffffff",
                null,
                null);
    }

    @Test
    void aTestRenderIsTheFirstSecondsSmallWithWhatFallsInThem() {
        EditJobParams.Trim s = VideoEditRules.sample(trim(10_000, 60_000L, null), 1920, 1080, 120_000L);
        assertEquals(10_000, s.startMs());
        assertEquals(18_000L, s.endMs());
        assertEquals(new MediaTools.ScaleSize(640, 360), s.scale());
        assertTrue(s.sample());
        // The fades no longer fit: the fade out goes. The freeze at 20 s is past the sample; one pause is in it.
        assertEquals(new VideoEditRules.Fade(3000, 0, "BLACK"), s.fade());
        assertNull(s.freeze());
        assertEquals(List.of(new VideoEditRules.Freeze(2000, 1000)), s.holds());
        assertEquals(List.of(new VideoEditRules.SpeedRange(6000, 8000, 2)), s.speeds());
        // The second punch-in is cut to 7.8–8 s, too short, so it goes.
        assertEquals(
                List.of(new VideoEditRules.Segment(1000, 3000L)), s.punchIn().ranges());
        assertEquals("#ffffff", s.progressBar());
    }

    @Test
    void aTestRenderKeepsAShortClipAndASmallSizeAndAPortraitShape() {
        EditJobParams.Trim s =
                VideoEditRules.sample(trim(0, 5000L, new MediaTools.ScaleSize(1080, 1920)), 1920, 1080, 6000L);
        assertEquals(5000L, s.endMs());
        assertEquals(new MediaTools.ScaleSize(360, 640), s.scale());
        assertNull(VideoEditRules.sample(trim(0, 5000L, null), 320, 180, 6000L).scale());
    }
}
