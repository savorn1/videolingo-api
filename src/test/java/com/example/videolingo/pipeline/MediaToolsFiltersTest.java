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

    @Test
    void warmthComesAfterTheAdjustmentAndSharpenBeforeTheBlur() {
        assertEquals(
                List.of("colorbalance=rm=0.150:bm=-0.150", "unsharp=5:5:0.8:5:5:0", "gblur=sigma=1.00"),
                MediaTools.lookFilters(new VideoEditRules.Look(0, 1, 1, 1, false, false, false, 0.5, true)));
        // Black & white has no colour to warm.
        assertEquals(
                List.of("eq=brightness=0.000:contrast=1.000:saturation=0.000"),
                MediaTools.lookFilters(new VideoEditRules.Look(0, 1, 1, 0, true, false, false, 0.5, false)));
    }

    @Test
    void steadyEffectsAreFiltersAndZoomsAreNotAmongThem() {
        assertEquals(List.of("rgbashift=rh=-6:bh=6", "noise=alls=10:allf=t"), MediaTools.effectFilters("GLITCH"));
        assertEquals(4, MediaTools.effectFilters("OLD_FILM").size());
        assertTrue(MediaTools.effectFilters("ZOOM_IN").isEmpty());
        assertTrue(MediaTools.effectFilters(null).isEmpty());
    }

    @Test
    void aZoomScalesOverTheClipThenCutsBackToSize() {
        assertEquals(
                List.of(
                        "scale=w='trunc(iw*(1+0.200*min(1,max(0,(t-1.000)/4.000)))/2)*2':h=-2:eval=frame",
                        "crop=640:360"),
                MediaTools.zoomFilters("ZOOM_IN", 1000, 5000, 640, 360));
        assertTrue(MediaTools.zoomFilters("ZOOM_OUT", 0, 2000, 640, 360).get(0).contains("(1-min(1"));
    }

    @Test
    void fadesAreTimedOnTheSourceClock() {
        VideoEditRules.Fade fade = new VideoEditRules.Fade(500, 1000, "WHITE");
        assertEquals(
                List.of("fade=t=in:st=2.000:d=0.500:color=white", "fade=t=out:st=9.000:d=1.000:color=white"),
                MediaTools.fadeFilters(fade, 2000, 10000, "fade"));
        assertEquals(
                List.of("afade=t=in:st=2.000:d=0.500", "afade=t=out:st=9.000:d=1.000"),
                MediaTools.fadeFilters(fade, 2000, 10000, "afade"));
        assertEquals(
                List.of("fade=t=out:st=0.000:d=1.000"),
                MediaTools.fadeFilters(new VideoEditRules.Fade(0, 1000, null), 0, 1000, "fade"));
    }

    @Test
    void theOutputSizeFollowsCropTurnAndResize() {
        assertEquals(
                List.of(1080, 1920),
                List.of(
                        MediaTools.outputSize(CROP, 90, SCALE, 1920, 1080)[0],
                        MediaTools.outputSize(CROP, 90, SCALE, 1920, 1080)[1]));
        int[] turned = MediaTools.outputSize(CROP, 90, null, 1920, 1080);
        assertEquals(List.of(360, 640), List.of(turned[0], turned[1]));
        int[] whole = MediaTools.outputSize(null, null, null, 1920, 1080);
        assertEquals(List.of(1920, 1080), List.of(whole[0], whole[1]));
        assertEquals(null, MediaTools.outputSize(null, null, null, null, null));
    }

    @Test
    void aFreezeHoldsTheFrameAndPausesTheSound() {
        String graph = MediaTools.freezeGraph(2000, 1500, true);
        assertTrue(graph.startsWith(
                "[0:v]trim=end=2.000,setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=1.500[v1];"));
        assertTrue(graph.contains("apad=pad_dur=1.500[a1]"));
        assertTrue(!MediaTools.freezeGraph(2000, 1500, false).contains("[0:a]"));
        assertEquals(
                "fps=12,scale=480:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse",
                MediaTools.gifFilter(480, 12));
    }

    @Test
    void blurBoxesComeFirstAndChainIntoTheCrop() {
        assertEquals(
                List.of(
                        "split=2[bm0][bt0];[bt0]crop=100:40:10:20,boxblur=luma_radius=9:luma_power=2[bb0];[bm0][bb0]overlay=10:20"),
                MediaTools.blurFilters(List.of(new VideoEditRules.BlurBox(10, 20, 100, 40))));
        assertTrue(MediaTools.blurFilters(null).isEmpty());
    }

    @Test
    void aSpeedRangeIsCutIntoPiecesAndJoined() {
        String g = MediaTools.speedGraph(new VideoEditRules.SpeedRange(1000, 2000, 0.5), true, 4000);
        assertTrue(g.contains("[0:v]trim=start=1.000:end=2.000,setpts=(PTS-STARTPTS)/0.5[v1]"), g);
        assertTrue(g.contains("atempo=0.5[a1]"), g);
        assertTrue(g.endsWith("[v0][a0][v1][a1][v2][a2]concat=n=3:v=1:a=1[v][a]"), g);
        // From the very start to the very end: one piece, no empty ones.
        String whole = MediaTools.speedGraph(new VideoEditRules.SpeedRange(0, 4000, 2.0), false, 4000);
        assertTrue(whole.endsWith("[v0]concat=n=1:v=1:a=0[v]"), whole);
        assertEquals(List.of("atempo=0.5", "atempo=0.5"), MediaTools.atempoChain(0.25));
        assertEquals(List.of("atempo=2", "atempo=2.0"), MediaTools.atempoChain(4.0));
        assertEquals(List.of("atempo=0.75"), MediaTools.atempoChain(0.75));
    }

    @Test
    void pictureInPictureSitsInItsCornerAndCardsAreJoinedAtOneSize() {
        assertEquals(
                "[1:v]scale=576:-2,setsar=1[p];[0:v][p]overlay=58:H-h-58:eof_action=pass[v]",
                MediaTools.pipGraph("BOTTOM_LEFT", 30, 1920));
        assertTrue(MediaTools.pipGraph("TOP_RIGHT", 30, 1920).contains("overlay=W-w-58:58"));
        String join = MediaTools.joinGraph(2, 1280, 720, true);
        assertTrue(join.contains("[1:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720"), join);
        assertTrue(join.endsWith("[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]"), join);
        assertTrue(MediaTools.joinGraph(2, 1280, 720, false).endsWith("[v0][v1]concat=n=2:v=1:a=0[v]"));
    }
}
