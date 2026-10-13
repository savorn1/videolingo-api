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

// Runs the real ffmpeg on the reverse, stabilise, zoom-to and green-screen passes: each keeps the clip's
// length, size and sound. Skipped where ffmpeg isn't installed.
class MotionFfmpegTest {

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

    /** A 3 s test picture with a tone. */
    private static Path clip(Path dir) {
        Path out = dir.resolve("clip.mp4");
        assertEquals(
                0,
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc=size=320x180:rate=25:duration=3",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=3",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-shortest",
                        out.toString())),
                "could not make the test clip");
        return out;
    }

    private static void assertSame3s(MediaTools media, Path out, Path dir) {
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertTrue(p.hasVideo());
        assertTrue(p.hasAudio());
        assertEquals(320, p.width());
        assertEquals(180, p.height());
        assertTrue(Math.abs(p.durationMs() - 3000) < 400, "got " + p.durationMs() + " ms");
    }

    @Test
    void reverse(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        assertSame3s(
                media, media.reverse(clip(dir).toString(), true, new JobContext(mock(JobStore.class), 1, dir)), dir);
    }

    @Test
    void stabilize(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        assertSame3s(
                media, media.stabilize(clip(dir).toString(), true, new JobContext(mock(JobStore.class), 1, dir)), dir);
    }

    @Test
    void zoomTo(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path out = media.zoomTo(
                clip(dir).toString(),
                new VideoEditRules.ZoomTo(0.8, 0.2, 0.4),
                320,
                180,
                3000,
                true,
                new JobContext(mock(JobStore.class), 1, dir));
        assertSame3s(media, out, dir);
    }

    @Test
    void greenScreen(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path out = media.greenScreen(
                clip(dir).toString(),
                new VideoEditRules.GreenScreen(null, 0.3, "#1e3a8a"),
                320,
                180,
                true,
                new JobContext(mock(JobStore.class), 1, dir));
        assertSame3s(media, out, dir);
    }
}
