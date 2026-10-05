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

// Burns a CaptionRules ASS file into a test video with the real ffmpeg, so a file
// libass rejects is caught here. Skipped where ffmpeg has no subtitles filter (it
// needs libass — the worker that burns subtitles must have it).
class CaptionsFfmpegTest {

    private static boolean ffmpeg;

    @BeforeAll
    static void findFfmpeg() {
        ffmpeg = run(List.of("ffmpeg", "-version")) == 0 && hasSubtitlesFilter();
    }

    private static boolean hasSubtitlesFilter() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-filters")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return out.lines().anyMatch(l -> l.matches("\\s*\\S+\\s+subtitles\\s.*"));
        } catch (IOException | InterruptedException e) {
            return false;
        }
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
    void twoLineSubtitlesBurnIntoTheVideo(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg without the subtitles filter (libass)");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path ass = dir.resolve("captions.ass");
        java.nio.file.Files.writeString(
                ass,
                CaptionRules.toAss(
                        CaptionRules.pair(
                                List.of(
                                        new CaptionRules.Line(0, 2500, "Hello {there}"),
                                        new CaptionRules.Line(2500, 5000, "Bye")),
                                List.of(new CaptionRules.Line(0, 2500, "Bonjour"))),
                        "BOX",
                        "BOTTOM",
                        6,
                        320,
                        180));
        Path out = media.burnSubtitles(src.toString(), ass, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasVideo() && p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 6000) < 400, "expected about 6 s, got " + p.durationMs() + " ms");
    }
}
