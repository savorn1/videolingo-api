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

// Runs the real ffmpeg on blurred areas, a part at another speed, a picture in
// picture and title cards, so a filter ffmpeg rejects is caught here. Skipped
// where ffmpeg isn't installed.
class Phase3FfmpegTest {

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

    private static long ms(MediaTools media, Path file, Path dir) {
        return probe(media, file, dir).durationMs();
    }

    @Test
    void blurredAreasKeepTheLengthAndWorkWithACrop(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path src = source(dir, true);
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.trim(
                src.toString(),
                0,
                3000L,
                new MediaTools.CropRect(0, 0, 160, 90),
                null,
                null,
                false,
                false,
                0,
                null,
                null,
                null,
                List.of(new VideoEditRules.BlurBox(10, 10, 80, 40), new VideoEditRules.BlurBox(200, 100, 60, 60)),
                ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertEquals(160, p.width());
        assertTrue(Math.abs(p.durationMs() - 3000) < 400, "got " + p.durationMs());
    }

    @Test
    void aSlowPartMakesTheClipLongerWithOrWithoutSound(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path withSound = source(dir, true);
        Path slow =
                media.speedRange(withSound.toString(), new VideoEditRules.SpeedRange(1000, 3000, 0.5), true, 6000, ctx);
        assertTrue(Math.abs(ms(media, slow, dir) - 8000) < 400, "expected about 8 s, got " + ms(media, slow, dir));
        Path silent = source(dir, false);
        Path fast = media.speedRange(silent.toString(), new VideoEditRules.SpeedRange(0, 6000, 4.0), false, 6000, ctx);
        assertTrue(Math.abs(ms(media, fast, dir) - 1500) < 400, "expected about 1.5 s, got " + ms(media, fast, dir));
    }

    @Test
    void aVideoInTheCornerKeepsTheMainLengthAndSound(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path main = source(dir, true);
        Path second = dir.resolve("second.mp4");
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
                        "testsrc=size=320x240:rate=25:duration=2",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        second.toString())));
        Path out = media.pip(main.toString(), second.toString(), "TOP_RIGHT", 30, 320, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 6000) < 400, "expected about 6 s, got " + p.durationMs());
    }

    @Test
    void cardsAreJoinedBeforeAndAfterTheClip(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path clip = source(dir, true);
        java.awt.image.BufferedImage logo =
                new java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Path png = dir.resolve("card.png");
        TextRenderer.write(
                CardRenderer.compose(
                        TextRenderer.render(CardRenderer.textLayer("Lesson 3", "#ffffff"), 180), logo, 320, 180),
                png);
        Path intro = media.card(png, "#1e3a8a", 320, 180, 2000, true, "intro", ctx);
        Path outro = media.card(png, "#000000", 320, 180, 1500, true, "outro", ctx);
        Path out = media.join(List.of(intro.toString(), clip.toString(), outro.toString()), 320, 180, true, ctx);
        MediaTools.Probe p = probe(media, out, dir);
        assertTrue(p.hasAudio());
        assertEquals(320, p.width());
        assertTrue(Math.abs(p.durationMs() - 9500) < 500, "expected about 9.5 s, got " + p.durationMs());
    }
}
