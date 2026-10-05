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

// Runs the real ffmpeg on a held frame, a GIF and a still picture, so a filter
// ffmpeg rejects is caught here. Skipped where ffmpeg isn't installed.
class ExportsFfmpegTest {

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

    @Test
    void aFreezeMakesTheClipLongerWithSound(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.freeze(src.toString(), 2000, 1500, true, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 7500) < 400, "expected about 7.5 s, got " + p.durationMs() + " ms");
    }

    @Test
    void aFreezeWorksOnASilentVideo(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, false);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.freeze(src.toString(), 1000, 3000, false, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && !p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 9000) < 400, "expected about 9 s, got " + p.durationMs() + " ms");
    }

    @Test
    void aGifAndAStillPictureAreMade(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path gif = media.gif(src.toString(), 1000, 3000, 320, VideoEditRules.GIF_FPS, ctx);
        byte[] g = java.nio.file.Files.readAllBytes(gif);
        assertEquals("GIF89a", new String(g, 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(320, probe(media, gif, dir).width());
        Path jpg = media.still(src.toString(), 2500, ctx);
        byte[] j = java.nio.file.Files.readAllBytes(jpg);
        assertTrue(j.length > 1000 && (j[0] & 0xFF) == 0xFF && (j[1] & 0xFF) == 0xD8, "a JPEG");
    }
}
