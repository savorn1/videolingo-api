package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Trims seek before decoding, so their filters run on the clip's own clock (0 = the trim's start).
// These look at the frames: a fade placed on the source's clock instead would land seconds late.
// Skipped where ffmpeg isn't installed.
class TrimSeekFfmpegTest {

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

    /** A 6 s bright test picture with a tone, in the given pixel format. */
    private static Path source(Path dir, String pixFmt) {
        Path out = dir.resolve("src-" + pixFmt + ".mp4");
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
                        "testsrc=size=320x180:rate=25:duration=6",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=6",
                        "-c:a",
                        "aac",
                        "-shortest",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        pixFmt,
                        out.toString())),
                "could not make the test video");
        return out;
    }

    private static JobContext ctx(Path dir) {
        return new JobContext(mock(JobStore.class), 1, dir);
    }

    /** Average brightness 0–255 of the frame at `atMs`. */
    private static double brightness(MediaTools media, Path video, long atMs, Path dir) throws IOException {
        BufferedImage img = ImageIO.read(media.frame(video, atMs, ctx(dir)).toFile());
        long sum = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
            }
        }
        return sum / (3.0 * img.getWidth() * img.getHeight());
    }

    private static void assertLength(MediaTools media, Path out, Path dir, long expectMs) {
        long ms = media.probe(out.toString(), dir).durationMs();
        assertTrue(Math.abs(ms - expectMs) < 300, "expected about " + expectMs + " ms, got " + ms + " ms");
    }

    @Test
    void aTrimFromTheMiddleFadesInAtItsOwnStart(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path out = media.trim(
                source(dir, "yuv420p").toString(),
                3000,
                5000L,
                null,
                null,
                null,
                false,
                false,
                0,
                null,
                null,
                new VideoEditRules.Fade(1000, 0, null),
                ctx(dir));
        assertLength(media, out, dir, 2000);
        assertTrue(brightness(media, out, 0, dir) < 25, "the clip should open on black");
        assertTrue(brightness(media, out, 1500, dir) > 60, "the fade should be over a second in");
    }

    @Test
    void aTrimToTheEndFadesOutAtTheVideosEnd(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path out = media.trim(
                source(dir, "yuv420p").toString(),
                4000,
                null,
                null,
                null,
                null,
                false,
                false,
                0,
                null,
                null,
                new VideoEditRules.Fade(0, 1000, null),
                ctx(dir));
        assertLength(media, out, dir, 2000);
        assertTrue(brightness(media, out, 300, dir) > 60, "the clip should start bright");
        assertTrue(brightness(media, out, 1920, dir) < 25, "the clip should have faded out by its end");
    }

    @Test
    void aFullColourSourceComesOutPlayableInBrowsers(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        Path out = media().trim(source(dir, "yuv444p").toString(), 0, 2000L, null, null, ctx(dir));
        Process p = new ProcessBuilder(
                        "ffprobe",
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-show_entries",
                        "stream=pix_fmt",
                        "-of",
                        "csv=p=0",
                        out.toString())
                .redirectErrorStream(true)
                .start();
        String pixFmt = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor(30, TimeUnit.SECONDS);
        assertEquals("yuv420p", pixFmt);
    }
}
