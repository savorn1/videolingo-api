package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.MergeRules.Part;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the real ffmpeg on the command MergeRules builds, with clips that do not
// match each other (size, frame rate, sound layout, one with no sound at all).
// Skipped where ffmpeg isn't installed.
class MergeFfmpegTest {

    private static boolean ffmpeg;

    @BeforeAll
    static void findFfmpeg() {
        ffmpeg = run(List.of("ffmpeg", "-version"), null).exit == 0;
    }

    private record Result(int exit, String output) {}

    private static Result run(List<String> cmd, Path dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (dir != null) {
                pb.directory(dir.toFile());
            }
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, out + "\n(timed out)");
            }
            return new Result(p.exitValue(), out);
        } catch (IOException | InterruptedException e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static Path clip(Path dir, String name, String video, String audio, double seconds) {
        Path out = dir.resolve(name);
        List<String> cmd = new java.util.ArrayList<>(
                List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", video));
        if (audio != null) {
            cmd.addAll(List.of("-f", "lavfi", "-i", audio));
        }
        cmd.addAll(List.of("-t", String.valueOf(seconds), "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        if (audio != null) {
            cmd.addAll(List.of("-c:a", "aac"));
        }
        cmd.add(out.toString());
        Result r = run(cmd, dir);
        assertEquals(0, r.exit, r.output);
        return out;
    }

    private static double durationSeconds(String info) {
        Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)").matcher(info);
        assertTrue(m.find(), info);
        return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
    }

    private void joinAndCheck(String transition, Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        // Three clips that disagree on almost everything.
        Path a = clip(
                dir,
                "a.mp4",
                "testsrc=size=640x360:rate=25",
                "sine=frequency=440:sample_rate=44100",
                2.0); // 16:9, 25 fps, mono
        Path b = clip(
                dir,
                "b.mp4",
                "testsrc=size=320x240:rate=15",
                "sine=frequency=660:sample_rate=22050",
                1.5); // 4:3, 15 fps
        Path c = clip(dir, "c.mp4", "testsrc=size=400x400:rate=30", null, 1.0); // square, no sound at all
        List<Part> parts = List.of(new Part(2000, true), new Part(1500, true), new Part(1000, false));
        Size size = new Size(640, 360);
        Path out = dir.resolve("joined.mp4");

        List<String> cmd = MergeRules.command("ffmpeg", List.of(a, b, c), parts, size, transition, out);
        Result r = run(cmd, dir);
        assertEquals(0, r.exit, r.output + "\n" + String.join(" ", cmd));

        String info = run(List.of("ffmpeg", "-hide_banner", "-i", out.toString()), null).output;
        assertTrue(info.contains("Video: h264"), info);
        assertTrue(info.contains("640x360"), info);
        assertTrue(info.contains("Audio: aac") && info.contains("48000 Hz") && info.contains("stereo"), info);
        double total = durationSeconds(info);
        double expected = MergeRules.totalMs(parts, transition) / 1000.0;
        assertTrue(Math.abs(total - expected) < 0.25, "expected about " + expected + " s, got " + total + "\n" + info);
    }

    @Test
    void mismatchedClipsAreJoinedIntoOne(@TempDir Path dir) {
        joinAndCheck("NONE", dir);
    }

    @Test
    void mismatchedClipsAreJoinedWithFades(@TempDir Path dir) {
        joinAndCheck("FADE", dir);
    }

    @Test
    void mismatchedClipsAreJoinedWithFadesThroughWhite(@TempDir Path dir) {
        joinAndCheck("FADE_WHITE", dir);
    }

    @Test
    void mismatchedClipsAreDissolvedTogetherAndComeOutShorter(@TempDir Path dir) {
        joinAndCheck("DISSOLVE", dir);
    }

    @Test
    void mismatchedClipsAreWipedTogether(@TempDir Path dir) {
        joinAndCheck("WIPE", dir);
    }

    @Test
    void mismatchedClipsAreSlidTogether(@TempDir Path dir) {
        joinAndCheck("SLIDE", dir);
    }
}
