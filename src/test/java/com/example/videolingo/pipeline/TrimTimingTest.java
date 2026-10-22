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

// Timing on a trim: several held frames ("pause after each sentence") and several parts at another speed.
class TrimTimingTest {

    private static VideoEditRules.Freeze hold(long at, long d) {
        return new VideoEditRules.Freeze(at, d);
    }

    @Test
    void holdsAreCheckedLikeAFreezeInOrderAndApart() {
        assertNull(VideoEditRules.validateHolds(List.of(hold(1000, 1500), hold(4000, 1500)), 10_000L));
        assertNotNull(VideoEditRules.validateHolds(List.of(hold(4000, 1500), hold(1000, 1500)), 10_000L));
        assertNotNull(VideoEditRules.validateHolds(List.of(hold(1000, 1500), hold(1200, 1500)), 10_000L));
        assertNotNull(VideoEditRules.validateHolds(List.of(hold(12_000, 1500)), 10_000L));
        assertTrue(
                VideoEditRules.validateHolds(List.of(hold(1000, 200)), 10_000L).startsWith("Pause 1: "));
        assertEquals("2 pauses of 1.5 s", VideoEditRules.describeHolds(List.of(hold(1000, 1500), hold(4000, 1500))));
    }

    @Test
    void severalSpeedPartsAreCheckedTogetherAndMoveLaterMoments() {
        VideoEditRules.SpeedRange slow = new VideoEditRules.SpeedRange(1000, 2000, 0.5);
        VideoEditRules.SpeedRange fast = new VideoEditRules.SpeedRange(4000, 6000, 2.0);
        assertNull(VideoEditRules.validateSpeedParts(fast, List.of(slow), 10_000L));
        assertTrue(VideoEditRules.validateSpeedParts(
                        slow, List.of(new VideoEditRules.SpeedRange(1500, 3000, 2.0)), 10_000L)
                .contains("overlap"));
        assertTrue(VideoEditRules.validateSpeedParts(
                        slow, List.of(new VideoEditRules.SpeedRange(3000, 3100, 2.0)), 10_000L)
                .startsWith("Speed part 2: "));
        List<VideoEditRules.SpeedRange> parts = VideoEditRules.speedParts(fast, List.of(slow));
        assertEquals(1000, parts.get(0).startMs());
        assertEquals(500, VideoEditRules.rampedAcross(500, parts));
        assertEquals(3000, VideoEditRules.rampedAcross(2000, parts)); // +1 s from the slow part
        assertEquals(5500, VideoEditRules.rampedAcross(5000, parts)); // +1 s, then the first 1 s at 2× takes 0.5 s off
        assertEquals(10_000, VideoEditRules.rampedAcross(10_000, parts)); // +1 s, then −1 s
        assertEquals("2 parts at another speed", VideoEditRules.describeSpeedParts(parts));
        String g = MediaTools.speedGraph(parts, false, 10_000);
        assertTrue(g.contains("[0:v]trim=end=1.000,setpts=PTS-STARTPTS[v0]"), g);
        assertTrue(g.contains("[0:v]trim=start=1.000:end=2.000,setpts=(PTS-STARTPTS)/0.5[v1]"), g);
        assertTrue(g.contains("[0:v]trim=start=2.000:end=4.000,setpts=PTS-STARTPTS[v2]"), g);
        assertTrue(g.contains("[0:v]trim=start=6.000,setpts=PTS-STARTPTS[v4]"), g);
        assertTrue(g.endsWith("concat=n=5:v=1:a=0[v]"), g);
    }

    @Test
    void aFreezeOnAPausesMomentIsNotHeldTwice() {
        List<long[]> at = VideoEditRules.spacedHolds(
                List.of(new long[] {4000, 1000}, new long[] {1000, 2000}, new long[] {4200, 1500}));
        assertEquals(2, at.size());
        assertEquals(1000, at.get(0)[0]);
        assertEquals(4000, at.get(1)[0]);
    }

    @Test
    void theGraphCutsAtEachHoldAndJoinsThePiecesAgain() {
        String g = MediaTools.holdsGraph(List.of(new long[] {1000, 2000}, new long[] {3000, 500}), true);
        assertTrue(g.startsWith("[0:v]split=3[vs0][vs1][vs2];[0:a]asplit=3[as0][as1][as2]"), g);
        assertTrue(
                g.contains(
                        "[vs0]trim=start=0.000:end=1.000,setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=2.000[v0]"),
                g);
        assertTrue(g.contains("[vs2]trim=start=3.000,setpts=PTS-STARTPTS[v2]"), g);
        assertTrue(g.endsWith("[v0][a0][v1][a1][v2][a2]concat=n=3:v=1:a=1[v][a]"), g);
    }

    @Test
    void itRendersTheClipLongerByThePauses(@TempDir Path dir) {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        Path clip = dir.resolve("c.mp4");
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
                        clip.toString())));
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        Path out = media.holdFrames(
                clip.toString(),
                List.of(new long[] {1000, 1000}, new long[] {2000, 500}),
                true,
                new JobContext(mock(JobStore.class), 1, dir));
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertTrue(p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 4500) < 400, "got " + p.durationMs());
    }

    @Test
    void itRendersTwoPartsAtOtherSpeeds(@TempDir Path dir) {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        Path clip = dir.resolve("c.mp4");
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
                        "testsrc=size=320x180:rate=25:duration=4",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=4",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-shortest",
                        clip.toString())));
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        Path out = media.speedParts(
                clip.toString(),
                List.of(new VideoEditRules.SpeedRange(0, 1000, 0.5), new VideoEditRules.SpeedRange(2000, 4000, 2.0)),
                true,
                4000,
                new JobContext(mock(JobStore.class), 1, dir));
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertTrue(p.hasAudio());
        // 1 s → 2 s, 1 s as it is, 2 s → 1 s: 4 s in all.
        assertTrue(Math.abs(p.durationMs() - 4000) < 400, "got " + p.durationMs());
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
