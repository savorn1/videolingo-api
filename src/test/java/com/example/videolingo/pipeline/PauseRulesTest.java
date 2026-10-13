package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.videolingo.pipeline.AudioToVideoRules.Silence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PauseRulesTest {

    @Test
    void theQuietLevelMustBeSensible() {
        assertNull(PauseRules.validateNoise(-40));
        assertNull(PauseRules.validateNoise(-20));
        assertNotNull(PauseRules.validateNoise(-70));
        assertNotNull(PauseRules.validateNoise(0));
    }

    @Test
    void pausesAreClampedToTheVideoAndTinyOnesDropped() {
        List<Silence> found = List.of(new Silence(-50, 800), new Silence(3000, 3100), new Silence(9500, 12_000));
        assertEquals(List.of(new Silence(0, 800), new Silence(9500, 10_000)), PauseRules.clamp(found, 10_000));
    }

    /** Real ffmpeg: a video whose tone stops for two seconds in the middle has that pause found. */
    @Test
    void aPauseInAVideoIsFound(@TempDir Path dir) throws Exception {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        Path video = dir.resolve("talk.mp4");
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
                        "testsrc=size=160x90:rate=25:duration=6",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=300:duration=6",
                        "-af",
                        "volume=enable='between(t,2,4)':volume=0",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-shortest",
                        video.toString())));
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        List<Silence> pauses =
                PauseRules.clamp(media.silences(video.toString(), -40, PauseRules.MIN_PAUSE_MS, dir), 6_000);
        assertEquals(1, pauses.size(), pauses.toString());
        assertTrue(Math.abs(pauses.get(0).startMs() - 2000) < 200, pauses.toString());
        assertTrue(Math.abs(pauses.get(0).endMs() - 4000) < 200, pauses.toString());
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
