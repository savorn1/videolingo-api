package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Scene changes: ffmpeg's showinfo lines read and tidied (SceneRules), and a real run on two coloured scenes.
class SceneRulesTest {

    @Test
    void sensitivityPicksTheThreshold() {
        assertEquals(0.35, SceneRules.threshold(null));
        assertEquals(0.22, SceneRules.threshold("HIGH"));
        assertNull(SceneRules.threshold("MAX"));
    }

    @Test
    void showinfoTimesAreRead() {
        String log = """
                [Parsed_showinfo_2 @ 0x1] n:   0 pts:  51200 pts_time:2.04    duration:512 fmt:yuv420p
                frame=  1 fps=0.0 q=-0.0 size=N/A time=00:00:02.04
                [Parsed_showinfo_2 @ 0x1] n:   1 pts: 102400 pts_time:12.5 duration:512
                """;
        assertEquals(List.of(2040L, 12_500L), SceneRules.parse(log));
    }

    @Test
    void cutsAreKeptApartAndAwayFromTheEnds() {
        assertEquals(List.of(2000L, 5000L), SceneRules.tidy(List.of(5000L, 400L, 2000L, 2500L, 9500L), 10_000));
    }

    @Test
    void aRealSceneChangeIsFound(@TempDir Path dir) throws Exception {
        boolean ffmpeg = new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        Path clip = dir.resolve("two-scenes.mp4");
        Process p = new ProcessBuilder(
                        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "lavfi", "-i", "testsrc=size=320x180:rate=25:duration=2",
                        "-f", "lavfi", "-i", "color=c=blue:size=320x180:rate=25:duration=2",
                        "-filter_complex", "[0:v][1:v]concat=n=2:v=1[v]", "-map", "[v]",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", clip.toString())
                .inheritIO()
                .start();
        assertEquals(0, p.waitFor());
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        List<Long> cuts = SceneRules.tidy(media.sceneChanges(clip.toString(), 0.35, dir), 4000);
        assertEquals(1, cuts.size(), "got " + cuts);
        assertTrue(Math.abs(cuts.get(0) - 2000) <= 80, "got " + cuts);
    }
}
