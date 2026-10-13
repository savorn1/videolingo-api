package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.example.videolingo.pipeline.AudioEditRules.Music;
import com.example.videolingo.pipeline.AudioEditRules.Spec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the real ffmpeg on audio edits with extra sound tracks (some ducked, some looping) and with the
// picture re-timed while the sound keeps its speed. Skipped where ffmpeg isn't installed.
class AudioTracksFfmpegTest {

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

    /** A 6 s test picture with a tone. */
    private static Path video(Path dir) {
        Path out = dir.resolve("video.mp4");
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
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-shortest",
                        out.toString())),
                "could not make the test video");
        return out;
    }

    /** A short tone as an audio file, like an uploaded sound. */
    private static Path tone(Path dir, String name, int hz, double seconds) {
        Path out = dir.resolve(name + ".wav");
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
                        "sine=frequency=" + hz + ":duration=" + seconds,
                        out.toString())),
                "could not make a test sound");
        return out;
    }

    private static Spec spec(Music music, List<Music> tracks, double speed, boolean keepSoundSpeed) {
        return new Spec(
                null,
                List.of(),
                List.of(),
                1,
                0,
                0,
                false,
                "OFF",
                false,
                speed,
                0,
                0,
                "KEEP",
                music,
                tracks,
                keepSoundSpeed);
    }

    private static void assertLength(MediaTools media, Path out, Path dir, long expectMs) {
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertTrue(p.hasVideo());
        assertTrue(p.hasAudio());
        assertTrue(
                Math.abs(p.durationMs() - expectMs) < 400,
                "expected about " + expectMs + " ms, got " + p.durationMs() + " ms");
    }

    @Test
    void musicAndTwoTracksSomeDuckedSomeLoopingMixIntoTheVideosLength(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path video = video(dir);
        Music music = new Music("audio-uploads/m.wav", 0.3, true, true, 0);
        Music fx = new Music("audio-uploads/fx.wav", 1, false, false, 2_000);
        Music bed = new Music("audio-uploads/bed.wav", 0.5, true, true, 1_000);
        Spec s = spec(music, List.of(fx, bed), 1, false);
        AudioEditRules.Graph graph = AudioEditRules.build(s, new AudioEditRules.Inputs(6_000, true, false, 6_000L));
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.editAudio(
                video.toString(),
                null,
                List.of(
                        new MediaTools.Bed(tone(dir, "m", 220, 1.5), true),
                        new MediaTools.Bed(tone(dir, "fx", 880, 0.5), false),
                        new MediaTools.Bed(tone(dir, "bed", 330, 2), true)),
                graph,
                6_000,
                ctx);
        assertLength(media, out, dir, 6_000);
    }

    @Test
    void slowMotionWithTheSoundAtItsOwnSpeedIsTwiceAsLong(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path video = video(dir);
        Spec s = spec(null, List.of(), 0.5, true);
        AudioEditRules.Graph graph = AudioEditRules.build(s, new AudioEditRules.Inputs(6_000, true, false, 6_000L));
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.editAudio(video.toString(), null, List.of(), graph, 12_000, ctx);
        assertLength(media, out, dir, 12_000);
    }

    @Test
    void doubleSpeedWithTheSoundAtItsOwnSpeedIsCutAtTheNewEnd(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path video = video(dir);
        Spec s = spec(null, List.of(new Music("audio-uploads/fx.wav", 1, false, false, 500)), 2, true);
        AudioEditRules.Graph graph = AudioEditRules.build(s, new AudioEditRules.Inputs(6_000, true, false, 6_000L));
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path out = media.editAudio(
                video.toString(), null, List.of(new MediaTools.Bed(tone(dir, "fx", 880, 1), false)), graph, 3_000, ctx);
        assertLength(media, out, dir, 3_000);
    }
}
