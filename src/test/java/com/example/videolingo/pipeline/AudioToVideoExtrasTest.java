package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.pipeline.AudioToVideoRules.Range;
import com.example.videolingo.pipeline.AudioToVideoRules.Silence;
import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.AudioToVideoRules.Slide;
import com.example.videolingo.pipeline.AudioToVideoRules.Spec;
import java.util.List;
import org.junit.jupiter.api.Test;

// The shapes, moving and dissolving pictures, the strip and logo, and the chapter rules.
class AudioToVideoExtrasTest {

    private static final String AUDIO = "audio-uploads/a.mp3";
    private static final List<Slide> TWO =
            List.of(new Slide("overlay-uploads/a.png", 0), new Slide("overlay-uploads/b.png", 3000));

    private static Spec spec(
            List<Slide> slides,
            String shape,
            boolean motion,
            boolean crossfade,
            String strip,
            String logo,
            String wave) {
        return new Spec(
                AUDIO, null, "#111827", "720p", wave, null, false, null, false, false, slides, shape, motion, crossfade,
                strip, logo);
    }

    @Test
    void shapesTurnOrSquareTheFrame() {
        assertEquals(new Size(1280, 720), AudioToVideoRules.size("720p", null));
        assertEquals(new Size(720, 1280), AudioToVideoRules.size("720p", "TALL"));
        assertEquals(new Size(1080, 1080), AudioToVideoRules.size("1080p", "SQUARE"));
    }

    @Test
    void newOptionsAreChecked() {
        assertNull(AudioToVideoRules.validate(
                spec(TWO, "TALL", true, true, "Episode 4", "overlay-uploads/logo.png", null)));
        assertNotNull(AudioToVideoRules.validate(spec(TWO, "ROUND", false, false, null, null, null)));
        assertNotNull(AudioToVideoRules.validate(spec(TWO, null, false, false, "x".repeat(121), null, null)));
        assertNotNull(AudioToVideoRules.validate(spec(TWO, null, false, false, null, "videos/1.mp4", null)));
    }

    @Test
    void movingOrDissolvingPicturesNeedASmoothFrameRate() {
        assertEquals(
                AudioToVideoRules.SMOOTH_FPS, AudioToVideoRules.fps(spec(TWO, null, true, false, null, null, null)));
        assertEquals(
                AudioToVideoRules.SMOOTH_FPS, AudioToVideoRules.fps(spec(TWO, null, false, true, null, null, null)));
        // A dissolve needs two pictures, a zoom at least one.
        assertEquals(
                AudioToVideoRules.STILL_FPS,
                AudioToVideoRules.fps(spec(TWO.subList(0, 1), null, false, true, null, null, null)));
        assertEquals(
                AudioToVideoRules.STILL_FPS,
                AudioToVideoRules.fps(spec(List.of(), null, true, false, null, null, null)));
    }

    @Test
    void dissolvesOverlapEachPictureButTheLastAndStartWhereTheNextOneDoes() {
        Spec s = spec(TWO, null, false, true, null, null, null);
        assertEquals(List.of(3800L, 3000L), AudioToVideoRules.inputLengths(s, List.of(3000L, 3000L)));
        String graph =
                AudioToVideoRules.filterGraph(s, new Size(1280, 720), 2, false, List.of(3000L, 3000L), false, false);
        assertTrue(graph.contains("[s0][s1]xfade=transition=fade:duration=0.800:offset=3.000[pic]"), graph);
        assertFalse(graph.contains("concat"), graph);
    }

    @Test
    void aMovingPictureGrowsOverItsOwnTime() {
        String graph = AudioToVideoRules.filterGraph(
                spec(TWO.subList(0, 1), null, true, false, null, null, null),
                new Size(1280, 720),
                1,
                false,
                List.of(6000L),
                false,
                false);
        assertTrue(graph.contains("scale=w='trunc(1280*(1+0.060*t/6.000)/2)*2':h=-2:eval=frame,crop=1280:720"), graph);
    }

    @Test
    void theStripGoesAboveABottomWaveformAndTheLogoTopRight() {
        String withWave = AudioToVideoRules.filterGraph(
                spec(List.of(), null, false, false, "Ep 4", "overlay-uploads/l.png", "WAVES"),
                new Size(1280, 720),
                0,
                false,
                List.of(),
                true,
                true);
        // Inputs: 0 colour, 1 sound, 2 strip, 3 logo.
        assertTrue(withWave.contains("[pic][2:v]overlay=51:36:format=auto[striped]"), withWave);
        assertTrue(
                withWave.contains("[3:v]scale=130:-1[logo];[striped][logo]overlay=W-w-29:29:format=auto[logoed]"),
                withWave);
        String noWave = AudioToVideoRules.filterGraph(
                spec(List.of(), null, false, false, "Ep 4", null, null),
                new Size(1280, 720),
                0,
                false,
                List.of(),
                true,
                false);
        assertTrue(noWave.contains("overlay=51:H-h-58:format=auto[striped]"), noWave);
    }

    @Test
    void silencesAreReadFromTheLog() {
        String log = """
                [silencedetect @ 0x1] silence_start: -0.01
                [silencedetect @ 0x1] silence_end: 2.000068 | silence_duration: 2.0
                size=N/A time=00:00:06
                [silencedetect @ 0x1] silence_start: 4.999977
                [silencedetect @ 0x1] silence_end: 6.5 | silence_duration: 1.5
                """;
        assertEquals(List.of(new Silence(0, 2000), new Silence(5000, 6500)), AudioToVideoRules.parseSilences(log));
    }

    @Test
    void chaptersAreCutAtTheNearestPause() {
        long min = 60_000;
        // 25 min, parts of 10: pauses near 9:50 and 20:30 are used.
        List<Range> parts = AudioToVideoRules.chapterRanges(
                List.of(
                        new Silence(9 * min + 48_000, 9 * min + 52_000),
                        new Silence(20 * min + 29_000, 20 * min + 31_000)),
                25 * min,
                10 * min);
        assertEquals(List.of(new Range(0, 9 * min + 50_000), new Range(9 * min + 50_000, 25 * min)), parts);
    }

    @Test
    void withoutPausesTheCutIsRightOnTimeAndAShortRestJoinsTheLastPart() {
        long min = 60_000;
        assertEquals(
                List.of(new Range(0, 10 * min), new Range(10 * min, 20 * min), new Range(20 * min, 34 * min)),
                AudioToVideoRules.chapterRanges(List.of(), 34 * min, 10 * min));
        assertEquals(List.of(new Range(0, 12 * min)), AudioToVideoRules.chapterRanges(List.of(), 12 * min, 10 * min));
        // A pause too far from the mark isn't used.
        assertEquals(
                new Range(0, 10 * min),
                AudioToVideoRules.chapterRanges(List.of(new Silence(4 * min, 4 * min + 2000)), 30 * min, 10 * min)
                        .get(0));
    }

    @Test
    void aPartOfTheRecordingIsAtLeastASecond() {
        assertNull(AudioToVideoRules.validateRange(null, null));
        assertNull(AudioToVideoRules.validateRange(1000L, 5000L));
        assertNotNull(AudioToVideoRules.validateRange(1000L, 1500L));
        assertNotNull(AudioToVideoRules.validateRange(-5L, null));
        assertTrue(AudioToVideoRules.describe(spec(TWO, "TALL", true, true, "Ep 4", "overlay-uploads/l.png", null))
                .contains("720×1280, 2 pictures, moving pictures, dissolves, title strip, logo"));
    }

    @Test
    void theWaveformCanBeTallerOrShorter() {
        Spec tall = new Spec(
                AUDIO, null, "#111827", "720p", "WAVES", null, false, null, false, false, List.of(), null, false, false,
                null, null, 60);
        assertNull(AudioToVideoRules.validate(tall));
        assertNotNull(AudioToVideoRules.validate(new Spec(
                AUDIO, null, "#111827", "720p", "WAVES", null, false, null, false, false, List.of(), null, false, false,
                null, null, 90)));
        assertTrue(AudioToVideoRules.filterGraph(tall, new Size(1280, 720), 0, false)
                .contains("showwaves=s=1280x432:"));
        // Left out: a quarter of the frame along the bottom, 40 % for the bars in the middle.
        assertEquals(180, AudioToVideoRules.waveHeight(new Size(1280, 720), null));
        assertEquals(
                288,
                AudioToVideoRules.barLayout("PULSE", new Size(1280, 720), null).height());
        assertEquals(
                504,
                AudioToVideoRules.barLayout("PULSE", new Size(1280, 720), 70).height());
    }
}
