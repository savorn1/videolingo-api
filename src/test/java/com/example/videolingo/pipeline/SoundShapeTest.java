package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.example.videolingo.pipeline.AudioEditRules.Point;
import com.example.videolingo.pipeline.AudioEditRules.Spec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The volume curve, the sound presets and the de-esser: checks, the filters, and real renders.
class SoundShapeTest {

    private static Spec spec(List<Point> curve, String preset, boolean deEss) {
        return new Spec(
                null, List.of(), List.of(), 1, 0, 0, false, "OFF", false, 1, 0, 0, "KEEP", null, List.of(), false,
                curve, preset, deEss);
    }

    @Test
    void theCurveIsCheckedAndDescribed() {
        List<Point> curve = List.of(new Point(0, 1), new Point(3000, 0.2));
        assertNull(AudioEditRules.validate(spec(curve, null, false), 6000L));
        assertNotNull(AudioEditRules.validate(
                spec(List.of(new Point(3000, 1), new Point(1000, 1)), null, false), 6000L));
        assertNotNull(AudioEditRules.validate(spec(List.of(new Point(0, 2.5)), null, false), 6000L));
        assertNotNull(AudioEditRules.validate(spec(List.of(), "LOUD", false), 6000L));
        assertEquals("Volume curve, phone call sound, de-essed", AudioEditRules.describe(spec(curve, "PHONE", true)));
        assertTrue(spec(List.of(), null, false).isNoop());
        assertTrue(!spec(List.of(), "RADIO", false).isNoop());
    }

    @Test
    void theCurveIsAStraightLineBetweenPointsAndFlatOutside() {
        String e = AudioEditRules.curveExpr(List.of(new Point(1000, 1), new Point(3000, 0)));
        assertEquals("if(lt(t,1),1,if(lt(t,3),1+(-1)*(t-1)/2,0))", e);
        String g = AudioEditRules.build(
                        spec(List.of(new Point(1000, 1), new Point(3000, 0)), "MORE_BASS", true),
                        new AudioEditRules.Inputs(6000, true, false, 6000L))
                .filter();
        assertTrue(g.contains("volume=eval=frame:volume='if(lt(t,1)"), g);
        assertTrue(g.contains("bass=g=6"), g);
        assertTrue(g.contains("deesser="), g);
    }

    @Test
    void everyPresetRendersAndTheCurveSilencesTheEnd(@TempDir Path dir) {
        assumeTrue(run(List.of("ffmpeg", "-version")).code == 0, "ffmpeg isn't installed");
        Path video = dir.resolve("v.mp4");
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
                                video.toString()))
                        .code);
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        for (String preset : List.of("MORE_BASS", "CLEARER_VOICE", "PHONE", "RADIO")) {
            Path work = dir.resolve(preset);
            work.toFile().mkdirs();
            // Full level for 2 s, down to nothing at 4 s, then silent.
            Spec s = spec(List.of(new Point(2000, 1), new Point(4000, 0)), preset, true);
            Path out = media.editAudio(
                    video.toString(),
                    null,
                    List.of(),
                    AudioEditRules.build(s, new AudioEditRules.Inputs(6000, true, false, 6000L)),
                    6000,
                    new JobContext(mock(JobStore.class), 1, work));
            double start = meanVolume(out, 0, 1.5);
            double end = meanVolume(out, 4.5, 1);
            assertTrue(start > -40, preset + " start " + start);
            assertTrue(end < -60, preset + " end " + end);
        }
    }

    /** ffmpeg volumedetect's mean volume (dB) over a stretch; -200 for silence. */
    private static double meanVolume(Path file, double from, double seconds) {
        Result r = run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-ss",
                String.valueOf(from),
                "-t",
                String.valueOf(seconds),
                "-i",
                file.toString(),
                "-af",
                "volumedetect",
                "-vn",
                "-f",
                "null",
                "-"));
        Matcher m = Pattern.compile("mean_volume: (-?[0-9.]+|-inf) dB").matcher(r.output);
        assertTrue(m.find(), r.output);
        return m.group(1).equals("-inf") ? -200 : Double.parseDouble(m.group(1));
    }

    private record Result(int code, String output) {}

    private static Result run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, out);
            }
            return new Result(p.exitValue(), out);
        } catch (IOException | InterruptedException e) {
            return new Result(-1, String.valueOf(e));
        }
    }
}
