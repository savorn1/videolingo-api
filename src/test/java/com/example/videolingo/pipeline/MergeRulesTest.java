package com.example.videolingo.pipeline;

import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.MergeRules.Part;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeRulesTest {

    private static final Size HD = new Size(1280, 720);

    // ── the request ───────────────────────────────────────────────────────

    @Test
    void aValidRequestPasses() {
        assertNull(MergeRules.validate(List.of(1L, 2L), null, null));
        assertNull(MergeRules.validate(List.of(1L, 2L, 3L), "1080p", "FADE"));
    }

    @Test
    void thereMustBeBetweenTwoAndTenVideos() {
        assertNotNull(MergeRules.validate(null, null, null));
        assertNotNull(MergeRules.validate(List.of(), null, null));
        assertNotNull(MergeRules.validate(List.of(1L), null, null));
        assertNull(MergeRules.validate(java.util.stream.LongStream.rangeClosed(1, MergeRules.MAX_PARTS).boxed().toList(), null, null));
        assertNotNull(MergeRules.validate(java.util.stream.LongStream.rangeClosed(1, MergeRules.MAX_PARTS + 1).boxed().toList(), null, null));
    }

    @Test
    void aVideoCanOnlyBeUsedOnce() {
        assertNotNull(MergeRules.validate(List.of(1L, 2L, 1L), null, null));
        assertNotNull(MergeRules.validate(java.util.Arrays.asList(1L, null), null, null));
    }

    @Test
    void sizeAndTransitionAreChecked() {
        assertNotNull(MergeRules.validate(List.of(1L, 2L), "4k", null));
        assertNotNull(MergeRules.validate(List.of(1L, 2L), null, "WIPE"));
        assertNull(MergeRules.validate(List.of(1L, 2L), "360p", "NONE"));
    }

    // ── the files ─────────────────────────────────────────────────────────

    @Test
    void everyClipMustHaveALengthAndTheTotalIsCapped() {
        assertNull(MergeRules.validateParts(List.of(new Part(5_000, true), new Part(7_000, false))));
        assertNotNull(MergeRules.validateParts(List.of(new Part(5_000, true), new Part(0, true))));
        assertNotNull(MergeRules.validateParts(List.of(new Part(MergeRules.MAX_TOTAL_MS, true), new Part(1, true))));
        assertNull(MergeRules.validateParts(List.of(new Part(MergeRules.MAX_TOTAL_MS - 1000, true), new Part(1000, true))));
        assertEquals(12_000, MergeRules.totalMs(List.of(new Part(5_000, true), new Part(7_000, false))));
    }

    @Test
    void theLimitIsPutInPlainWords() {
        String message = MergeRules.validateParts(List.of(new Part(MergeRules.MAX_TOTAL_MS, true), new Part(60_000, true)));
        assertTrue(message.contains("3 h 1 min") && message.contains("3 h 0 min"), message);
    }

    // ── the graph ─────────────────────────────────────────────────────────

    @Test
    void everyClipIsBroughtToTheSameSizeRateAndSoundFormatThenJoined() {
        String graph = MergeRules.filterGraph(List.of(new Part(10_000, true), new Part(20_000, true)), HD, "NONE");
        assertTrue(graph.contains("[0:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=30,format=yuv420p[v0]"));
        assertTrue(graph.contains("[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,apad=whole_dur=20.000,atrim=duration=20.000,asetpts=PTS-STARTPTS[a1]"));
        assertTrue(graph.endsWith("[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]"));
        assertFalse(graph.contains("fade"));
    }

    @Test
    void aClipWithoutSoundGetsSilenceOfTheRightLength() {
        String graph = MergeRules.filterGraph(List.of(new Part(10_000, false), new Part(5_500, true)), HD, "NONE");
        assertTrue(graph.contains("anullsrc=r=48000:cl=stereo,atrim=duration=10.000,asetpts=PTS-STARTPTS[a0]"));
        assertFalse(graph.contains("[0:a]"));
    }

    @Test
    void fadesDipAtEachJointButNotAtTheVeryStartOrEnd() {
        String graph = MergeRules.filterGraph(List.of(new Part(10_000, true), new Part(10_000, true), new Part(10_000, true)), HD, "FADE");
        String first = graph.substring(graph.indexOf("[0:v]"), graph.indexOf("[v0]"));
        String middle = graph.substring(graph.indexOf("[1:v]"), graph.indexOf("[v1]"));
        String last = graph.substring(graph.indexOf("[2:v]"), graph.indexOf("[v2]"));
        assertFalse(first.contains("fade=t=in"));
        assertTrue(first.contains("fade=t=out:st=9.500:d=0.500"));
        assertTrue(middle.contains("fade=t=in:st=0:d=0.500") && middle.contains("fade=t=out:st=9.500:d=0.500"));
        assertTrue(last.contains("fade=t=in:st=0:d=0.500"));
        assertFalse(last.contains("fade=t=out"));
        // Sound fades with the picture.
        assertTrue(graph.contains("afade=t=in:st=0:d=0.500"));
        assertTrue(graph.contains("afade=t=out:st=9.500:d=0.500"));
    }

    @Test
    void aVeryShortClipGetsAShorterFade() {
        assertEquals(0.5, MergeRules.fadeFor(10_000), 1e-9);
        assertEquals(0.3, MergeRules.fadeFor(600), 1e-9);
        String graph = MergeRules.filterGraph(List.of(new Part(600, true), new Part(10_000, true)), HD, "FADE");
        assertTrue(graph.contains("fade=t=out:st=0.300:d=0.300"));
    }

    // ── the command ───────────────────────────────────────────────────────

    @Test
    void theCommandTakesTheClipsInOrderAndEncodesOnce() {
        List<String> cmd = MergeRules.command("ffmpeg", List.of(Path.of("a.mp4"), Path.of("b.mp4"), Path.of("c.mp4")),
                List.of(new Part(1000, true), new Part(2000, true), new Part(3000, false)), HD, "NONE", Path.of("out.mp4"));
        String line = String.join(" ", cmd);
        assertTrue(line.contains("-i a.mp4 -i b.mp4 -i c.mp4 -filter_complex"));
        assertTrue(line.contains("-map [v] -map [a] -c:v libx264"));
        assertTrue(line.contains("-r 30") && line.contains("-ar 48000"));
        assertEquals("out.mp4", cmd.get(cmd.size() - 1));
    }

    @Test
    void theDescriptionSaysWhatWillBeMade() {
        assertEquals("Join 3 videos (1280×720)", MergeRules.describe(3, null, "NONE"));
        assertEquals("Join 2 videos (1920×1080, fades)", MergeRules.describe(2, "1080p", "FADE"));
    }

    // ── carrying transcripts over ─────────────────────────────────────────

    @Test
    void eachClipStartsWhereTheOnesBeforeItEnd() {
        assertEquals(List.of(0L, 5_000L, 12_000L), MergeRules.offsets(List.of(new Part(5_000, true), new Part(7_000, true), new Part(3_000, false))));
        assertEquals(List.of(), MergeRules.offsets(List.of()));
    }

    @Test
    void segmentsAreMovedToWhereTheirClipSits() {
        List<MergeRules.Seg> shifted = MergeRules.shift(List.of(new MergeRules.Seg(0, 1_000, "a", "Ann"), new MergeRules.Seg(1_000, 2_500, "b", null)), 5_000, 10_000);
        assertEquals(List.of(new MergeRules.Seg(5_000, 6_000, "a", "Ann"), new MergeRules.Seg(6_000, 7_500, "b", null)), shifted);
    }

    @Test
    void textNeverSpillsIntoTheNextClip() {
        List<MergeRules.Seg> shifted = MergeRules.shift(List.of(
                new MergeRules.Seg(8_000, 12_000, "runs past the end", null),
                new MergeRules.Seg(10_000, 11_000, "starts at the end", null),
                new MergeRules.Seg(15_000, 16_000, "after the end", null)), 20_000, 10_000);
        assertEquals(1, shifted.size());
        assertEquals(new MergeRules.Seg(28_000, 30_000, "runs past the end", null), shifted.get(0));
    }

    @Test
    void aSegmentKeepsAtLeastAMillisecond() {
        List<MergeRules.Seg> shifted = MergeRules.shift(List.of(new MergeRules.Seg(4_000, 4_000, "blip", null)), 0, 10_000);
        assertEquals(1, shifted.get(0).endMs() - shifted.get(0).startMs());
    }

    @Test
    void onlyLanguagesEveryVideoHasAreCarried() {
        assertEquals(List.of("en"), MergeRules.commonLanguages(List.of(java.util.Set.of("en", "km"), java.util.Set.of("en"), java.util.Set.of("en", "fr"))));
        assertEquals(List.of(), MergeRules.commonLanguages(List.of(java.util.Set.of("en"), java.util.Set.of("km"))));
        assertEquals(List.of(), MergeRules.commonLanguages(List.of()));
        assertEquals(List.of("en"), MergeRules.commonLanguages(List.of(java.util.Set.of("en"))));
    }

    @Test
    void theOrderOfTheFirstVideoIsKept() {
        assertEquals(List.of("km", "en"), MergeRules.commonLanguages(List.of(new java.util.LinkedHashSet<>(List.of("km", "en")), java.util.Set.of("en", "km"))));
    }
}
