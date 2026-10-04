package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaToolsStallTest {

    @AfterEach
    void restore() {
        MediaTools.stall = Duration.ofMinutes(5);
    }

    @Test
    void aStuckFfmpegIsStoppedInsteadOfRunningToTheTimeout(@TempDir Path dir) throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg isn't installed here");
        MediaTools.stall = Duration.ofSeconds(3);
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        long started = System.nanoTime();
        // Reads its input from a pipe nobody writes to: no output, no progress.
        JobFailure failure = assertThrows(JobFailure.class, () -> media.exportAudio("pipe:0", "MP3", ctx));
        long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
        assertTrue(failure.getMessage().startsWith("ffmpeg stopped making progress"), failure.getMessage());
        assertTrue(seconds < 15, "took " + seconds + " s");
    }
}
