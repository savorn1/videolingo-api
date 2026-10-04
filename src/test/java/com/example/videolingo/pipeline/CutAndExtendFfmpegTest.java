package com.example.videolingo.pipeline;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

// Runs the real ffmpeg on the cut-out and extend-past-the-end renders, so a bad
// filter or a sound that drifts is caught here and not by the first job someone
// queues. Skipped where ffmpeg isn't installed.
class CutAndExtendFfmpegTest {

    private static boolean ffmpeg;

    @BeforeAll
    static void findFfmpeg() {
        ffmpeg = run(List.of("ffmpeg", "-version")) == 0;
    }

    private static int run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    private static MediaTools media() {
        return new MediaTools(new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    /** A 6 s test picture, with a tone when `sound`. */
    private static Path source(Path dir, boolean sound) {
        Path out = dir.resolve(sound ? "with-sound.mp4" : "silent.mp4");
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "testsrc=size=320x180:rate=25:duration=6"));
        if (sound) {
            cmd.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=6", "-c:a", "aac", "-shortest"));
        }
        cmd.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", out.toString()));
        assertEquals(0, run(cmd), "could not make the test video");
        return out;
    }

    private static MediaTools.Probe probe(MediaTools media, Path file, Path dir) {
        return media.probe(file.toString(), dir);
    }

    private static List<VideoEditRules.Segment> cuts(long... startEnd) {
        java.util.ArrayList<VideoEditRules.Segment> list = new java.util.ArrayList<>();
        for (int i = 0; i < startEnd.length; i += 2) {
            list.add(new VideoEditRules.Segment(startEnd[i], startEnd[i + 1]));
        }
        return list;
    }

    @Test
    void cuttingAMiddleRangeShortensPictureAndSoundTogether(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.cut(src.toString(), cuts(2000, 4000), true, 4000L, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 4000) < 300, "expected about 4 s, got " + p.durationMs() + " ms");
    }

    @Test
    void severalCutsAndACutToTheEnd(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        List<VideoEditRules.Segment> merged = VideoEditRules.mergeCuts(
                List.of(new VideoEditRules.Segment(1000, 2000L), new VideoEditRules.Segment(3000, null)));
        Path out = media.cut(src.toString(), merged, true, 2000L, ctx);
        long ms = probe(media, out, dir).durationMs();
        assertTrue(Math.abs(ms - 2000) < 300, "expected about 2 s, got " + ms + " ms");
    }

    @Test
    void aVideoWithoutSoundIsCutToo(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, false);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.cut(src.toString(), cuts(0, 3000), false, 3000L, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && !p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 3000) < 300, "expected about 3 s, got " + p.durationMs() + " ms");
    }

    @Test
    void anExtendedTrimRunsPastTheEndWithSound(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        // 6 s video, trimmed 1 s → 9 s: 3 s of it is held.
        Path out = media.trim(src.toString(), 1000, 9000L, null, null, null, false, false, 3000, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 8000) < 400, "expected about 8 s, got " + p.durationMs() + " ms");
    }

    @Test
    void anExtendedTrimOfASilentVideoWorks(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, false);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.trim(src.toString(), 0, 8000L, null, null, null, false, false, 2000, ctx);
        long ms = probe(media, out, dir).durationMs();
        assertTrue(Math.abs(ms - 8000) < 400, "expected about 8 s, got " + ms + " ms");
    }

    @Test
    void everyLookFilterRunsAndKeepsTheLength(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        VideoEditRules.Look look = new VideoEditRules.Look(0.1, 1.3, 1.5, 2, false, true, true);
        Path out = media.trim(src.toString(), 0, 4000L, null, null, null, false, false, 0, look, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 4000) < 300, "expected about 4 s, got " + p.durationMs() + " ms");
        Path mono = media.trim(src.toString(), 0, 3000L, null, null, null, false, false, 0, new VideoEditRules.Look(0, 1, 1, 0, true, false, false), ctx);
        assertTrue(probe(media, mono, dir).hasVideo());
    }
}
