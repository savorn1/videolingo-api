package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the real ffmpeg on trims with a filter, an effect and fades, so a filter
// string ffmpeg rejects is caught here and not by the first job someone queues.
// Skipped where ffmpeg isn't installed.
class EffectsFfmpegTest {

    private static boolean ffmpeg;

    @BeforeAll
    static void findFfmpeg() {
        ffmpeg = run(List.of("ffmpeg", "-version")) == 0;
    }

    private static int run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
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
        return new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    /** A 6 s test picture, with a tone when `sound`. */
    private static Path source(Path dir, boolean sound) {
        Path out = dir.resolve(sound ? "with-sound.mp4" : "silent.mp4");
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
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

    private static final VideoEditRules.Look WARM_SHARP =
            new VideoEditRules.Look(0, 1, 1, 0, false, false, false, 0.5, true);

    private static void assertClip(MediaTools media, Path out, Path dir, long expectMs, boolean sound) {
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo());
        assertEquals(sound, p.hasAudio());
        assertTrue(
                Math.abs(p.durationMs() - expectMs) < 400,
                "expected about " + expectMs + " ms, got " + p.durationMs() + " ms");
    }

    @Test
    void everyEffectRendersWithAFilterAndFades(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        for (String effect : VideoEditRules.EFFECTS) {
            JobContext ctx = new JobContext(mock(JobStore.class), 1, dir.resolve(effect));
            dir.resolve(effect).toFile().mkdirs();
            Path out = media.trim(
                    src.toString(),
                    1000,
                    4000L,
                    null,
                    null,
                    null,
                    false,
                    false,
                    0,
                    WARM_SHARP,
                    effect,
                    new VideoEditRules.Fade(500, 500, "WHITE"),
                    ctx);
            assertClip(media, out, dir, 3000, true);
        }
    }

    @Test
    void aZoomKeepsTheCroppedTurnedSize(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, false);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.trim(
                src.toString(),
                0,
                null,
                new MediaTools.CropRect(0, 0, 160, 120),
                null,
                90,
                false,
                false,
                0,
                null,
                "ZOOM_IN",
                new VideoEditRules.Fade(1000, 0, null),
                ctx);
        assertClip(media, out, dir, 6000, false);
        MediaTools.Probe p = probe(media, out, dir);
        assertEquals(120, p.width());
        assertEquals(160, p.height());
    }

    @Test
    void anExtendedTrimFadesOutAtItsNewEnd(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.trim(
                src.toString(),
                2000,
                8000L,
                null,
                null,
                null,
                false,
                false,
                2000,
                null,
                "ZOOM_OUT",
                new VideoEditRules.Fade(0, 1000, null),
                ctx);
        assertClip(media, out, dir, 6000, true);
    }
}
