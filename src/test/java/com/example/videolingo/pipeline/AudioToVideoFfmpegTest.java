package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.AudioToVideoRules.Spec;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the real ffmpeg on the commands AudioToVideoRules builds, so a bad filter
// graph is caught here and not by the first job someone queues. Skipped where
// ffmpeg isn't installed.
class AudioToVideoFfmpegTest {

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
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, out + "\n(timed out)");
            }
            return new Result(p.exitValue(), out);
        } catch (IOException | InterruptedException e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static Path tone(Path dir) {
        Path mp3 = dir.resolve("tone.mp3");
        Result r = run(
                List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=2",
                        mp3.toString()),
                dir);
        assertEquals(0, r.exit, r.output);
        return mp3;
    }

    private static Path picture(Path dir) throws IOException {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        Path png = dir.resolve("cover.png");
        javax.imageio.ImageIO.write(img, "png", png.toFile());
        return png;
    }

    private static void assertMp4(Path out, int width, int height) {
        Result probe = run(List.of("ffmpeg", "-hide_banner", "-i", out.toString()), null);
        String info = probe.output;
        assertTrue(info.contains("Video: h264"), info);
        assertTrue(info.contains("Audio: aac"), info);
        assertTrue(info.contains(width + "x" + height), info);
        assertTrue(info.contains("Duration: 00:00:02"), info);
    }

    private void render(Spec spec, List<Path> covers, Path title, Size size, Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        Path audio = tone(dir);
        Path out = dir.resolve("out.mp4");
        List<String> cmd =
                new ArrayList<>(AudioToVideoRules.command("ffmpeg", spec, audio, covers, title, size, 2_000, out));
        Result r = run(cmd, dir);
        assertEquals(0, r.exit, r.output + "\n" + String.join(" ", cmd));
        assertMp4(out, size.w(), size.h());
        assertTrue(Files.size(out) > 1000);
    }

    private static final String AUDIO = AudioEditRules.UPLOAD_PREFIX + "x.mp3";

    @Test
    void plainBackground(@TempDir Path dir) throws IOException {
        render(new Spec(AUDIO, null, "#101820", null), List.of(), null, new Size(640, 360), dir);
    }

    @Test
    void coverPicture(@TempDir Path dir) throws IOException {
        render(
                new Spec(AUDIO, OverlayRules.UPLOAD_PREFIX + "c.png", "#101820", null),
                List.of(picture(dir)),
                null,
                new Size(640, 360),
                dir);
    }

    @Test
    void wavesAndCleanedSound(@TempDir Path dir) throws IOException {
        render(
                new Spec(AUDIO, null, "#101820", null, "WAVES", null, false, null, true, true),
                List.of(),
                null,
                new Size(640, 360),
                dir);
    }

    @Test
    void barsOverACover(@TempDir Path dir) throws IOException {
        render(
                new Spec(
                        AUDIO,
                        OverlayRules.UPLOAD_PREFIX + "c.png",
                        "#ffffff",
                        null,
                        "BARS",
                        "#ff0000",
                        false,
                        null,
                        false,
                        false),
                List.of(picture(dir)),
                null,
                new Size(640, 360),
                dir);
    }

    @Test
    void everyWaveformStyleRenders(@TempDir Path dir) throws IOException {
        for (String style : new String[] {
            "SPIKES", "DOTS", "SPECTRUM", "PULSE", "BLOCKS", "FINE", "STRIPES", "REFLECT", "COLUMNS", "STEREO"
        }) {
            Path sub = Files.createDirectory(dir.resolve(style.toLowerCase()));
            render(
                    new Spec(AUDIO, null, "#101820", null, style, "#ffcc00", false, null, false, false),
                    List.of(),
                    null,
                    new Size(640, 360),
                    sub);
        }
    }

    @Test
    void aTestRenderIsAShortClipAtASmallSize(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        Path audio = tone(dir);
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        Spec spec = new Spec(AUDIO, null, "#0a0a9b", "360p", "PULSE", "#ffffff", false, null, false, false);
        Path out = media.audioToVideoQuick(spec, audio, 1500, dir);
        assertTrue(Files.size(out) > 1000, "the clip is empty");
        Result probe = run(
                List.of(
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "stream=width,height",
                        "-of",
                        "csv=p=0",
                        out.toString()),
                dir);
        assertTrue(probe.output.contains("640,360"), probe.output);
        Result streams = run(
                List.of(
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "stream=codec_type",
                        "-of",
                        "csv=p=0",
                        out.toString()),
                dir);
        assertTrue(streams.output.contains("audio"), "the test render has no sound: " + streams.output);
        assertTrue(streams.output.contains("video"), streams.output);
        // Not just present but audible: the tone must come through, not silence.
        Result loud = run(
                List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-i",
                        out.toString(),
                        "-af",
                        "volumedetect",
                        "-vn",
                        "-f",
                        "null",
                        "-"),
                dir);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("max_volume: (-?[0-9.]+) dB").matcher(loud.output);
        assertTrue(m.find(), loud.output);
        assertTrue(Double.parseDouble(m.group(1)) > -40, "the test render is silent: " + m.group(1) + " dB");
    }

    @Test
    void aBrokenTestRenderSaysSo(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = new MediaTools(
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));
        Spec spec = new Spec(
                dir.resolve("missing.mp3").toString(),
                null,
                "#0a0a9b",
                "360p",
                "PULSE",
                "#ffffff",
                false,
                null,
                false,
                false);
        JobFailure e = assertThrows(
                JobFailure.class, () -> media.audioToVideoQuick(spec, dir.resolve("missing.mp3"), 1500, dir));
        assertTrue(e.getMessage().startsWith("The test render failed"), e.getMessage());
    }

    @Test
    void titleCardWithWaveform(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        Spec spec = new Spec(
                AUDIO,
                null,
                "#101820",
                null,
                "WAVES",
                null,
                true,
                "Learning English with everyday stories",
                false,
                false);
        Size size = new Size(640, 360);
        // Drawn exactly as the job draws it.
        String wrapped = String.join("\n", AudioToVideoRules.wrapTitle(spec.titleText(), 24, 4));
        OverlayRules.Layer layer = new OverlayRules.Layer(
                "TEXT",
                wrapped,
                "SansSerif",
                700,
                7.0,
                AudioToVideoRules.contrastColor(spec.background()),
                null,
                0.0,
                "CENTER",
                null,
                0.0,
                0.5,
                0.5,
                1.0,
                0L,
                null,
                "NONE");
        Path title = dir.resolve("title.png");
        TextRenderer.write(TextRenderer.render(layer, size.h()), title);
        render(spec, List.of(), title, size, dir);
    }

    @Test
    void aSlideshowOfDifferentPicturesWithAWaveform(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        Path wide = picture(dir); // 200×100
        BufferedImage tall = new BufferedImage(60, 200, BufferedImage.TYPE_INT_ARGB);
        var g = tall.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 60, 200);
        g.dispose();
        Path tallPng = dir.resolve("tall.png");
        javax.imageio.ImageIO.write(tall, "png", tallPng.toFile());
        Spec spec = new Spec(
                AUDIO,
                null,
                "#101820",
                null,
                "WAVES",
                null,
                false,
                null,
                false,
                false,
                List.of(
                        new AudioToVideoRules.Slide(OverlayRules.UPLOAD_PREFIX + "a.png", 0),
                        new AudioToVideoRules.Slide(OverlayRules.UPLOAD_PREFIX + "b.png", 800)));
        render(spec, List.of(wide, tallPng), null, new Size(640, 360), dir);
    }
}
