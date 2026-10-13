package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Auto-reframe: the path, the expressions, and (with ffmpeg) following a box that slides across the picture.
class ReframeTest {

    @Test
    void itReplacesADrawnCropAndCantFollowATurn() {
        assertNull(ReframeRules.validate(new ReframeRules.Reframe(9 / 16.0), false, false));
        assertNotNull(ReframeRules.validate(new ReframeRules.Reframe(9 / 16.0), true, false));
        assertNotNull(ReframeRules.validate(new ReframeRules.Reframe(9 / 16.0), false, true));
        assertNotNull(ReframeRules.validate(new ReframeRules.Reframe(10), false, false));
    }

    @Test
    void stepsAreASecondOrLongerForLongClips() {
        assertEquals(1, ReframeRules.stepSeconds(30_000));
        assertEquals(10, ReframeRules.stepSeconds(600_000));
    }

    @Test
    void thePathIsSteadiedWithoutTrailingAndHoldsWhereNothingMoves() {
        double[] path = ReframeRules.smooth(new double[] {Double.NaN, 0.2, 0.2, Double.NaN, 0.8});
        // Before the first measurement: the first one; a still step keeps the last place.
        assertEquals(0.2, path[0], 1e-9);
        assertEquals(0.2, path[1], 1e-9);
        // Averaged with its neighbours: 0.2, 0.2, 0.8 → 0.4 — moving with the action, not behind it.
        assertEquals(0.4, path[3], 1e-9);
        assertEquals(0.5, path[4], 1e-9);
        assertEquals(0.5, ReframeRules.smooth(new double[] {Double.NaN, Double.NaN})[1], 1e-9);
        // A steady pan stays exactly on the action.
        double[] pan = ReframeRules.smooth(new double[] {0.1, 0.2, 0.3, 0.4});
        assertEquals(0.2, pan[1], 1e-9);
        assertEquals(0.3, pan[2], 1e-9);
    }

    @Test
    void theOffsetIsLinearBetweenStepsAndKeptInside() {
        // 1280 wide, a 405 wide crop: centred on 0.1 → 0 (kept inside), on 0.9 → 875 (the right edge).
        String x = ReframeRules.offsetExpression(new double[] {0.1, 0.9}, 1280, 405, 2);
        assertEquals("if(lt(t,2.000),0+875*(t-0.000)/2.000,875)", x);
        assertEquals("438", ReframeRules.offsetExpression(new double[] {0.5, 0.5}, 1280, 405, 1));
        assertEquals("0", ReframeRules.offsetExpression(new double[] {0.3}, 720, 720, 1));
    }

    @Test
    void itFollowsABoxSlidingAcross(@TempDir Path dir) {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        Path video = dir.resolve("slide.mp4");
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
                        "color=c=black:s=320x180:r=25:d=6",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=c=white:s=40x40:r=25:d=6",
                        "-filter_complex",
                        "[0:v][1:v]overlay=x='10+t*45':y=60:shortest=1",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        video.toString())));
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        double[][] track = media.motionTrack(video.toString(), 1, dir);
        assertTrue(track.length >= 5, "steps: " + track.length);
        assertTrue(
                track[track.length - 1][0] > track[0][0] + 0.3,
                "the path goes right: " + track[0][0] + " → " + track[track.length - 1][0]);

        double[] xs = new double[track.length];
        for (int i = 0; i < track.length; i++) {
            xs[i] = track[i][0];
        }
        String x = ReframeRules.offsetExpression(ReframeRules.smooth(xs), 320, 100, 1);
        Path out = media.reframe(
                video.toString(), 100, 180, x, "0", null, false, new JobContext(mock(JobStore.class), 1, dir));
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertEquals(100, p.width());
        assertEquals(180, p.height());
        assertTrue(Math.abs(p.durationMs() - 6000) < 400, "got " + p.durationMs());
        // The box is in the (narrow) picture early on and late on: the crop went along with it. A fixed crop would lose
        // it.
        assertTrue(brightest(out, 0.5) > 200, "box in view at 0.5 s");
        assertTrue(brightest(out, 5.5) > 200, "box in view at 5.5 s");
    }

    /** The brightest pixel of the frame at `seconds`, 0–255. */
    private static int brightest(Path video, double seconds) {
        try {
            Process p = new ProcessBuilder(
                            "ffmpeg",
                            "-v",
                            "error",
                            "-ss",
                            String.valueOf(seconds),
                            "-i",
                            video.toString(),
                            "-frames:v",
                            "1",
                            "-f",
                            "rawvideo",
                            "-pix_fmt",
                            "gray",
                            "-")
                    .start();
            byte[] bytes = p.getInputStream().readAllBytes();
            p.waitFor(30, TimeUnit.SECONDS);
            int max = 0;
            for (byte b : bytes) {
                max = Math.max(max, b & 0xff);
            }
            return max;
        } catch (IOException | InterruptedException e) {
            return -1;
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
}
