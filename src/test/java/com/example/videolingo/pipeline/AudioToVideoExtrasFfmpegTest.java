package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.AudioToVideoRules.Slide;
import com.example.videolingo.pipeline.AudioToVideoRules.Spec;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the real ffmpeg on the newer audio-to-video options: a tall frame with moving,
// dissolving pictures, a strip and a logo; silence trimming and a part of the
// recording; and finding chapters at the pauses. Skipped where ffmpeg isn't installed.
class AudioToVideoExtrasFfmpegTest {

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
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
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

    /** `silence` s of silence, `tone` s of sound, `silence` s of silence again. */
    private static Path recording(Path dir, double silence, double tone) {
        Path out = dir.resolve("recording.m4a");
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
                        "anullsrc=r=44100:cl=mono:d=" + silence,
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=" + tone,
                        "-f",
                        "lavfi",
                        "-i",
                        "anullsrc=r=44100:cl=mono:d=" + silence,
                        "-filter_complex",
                        "[0:a][1:a][2:a]concat=n=3:v=0:a=1",
                        "-c:a",
                        "aac",
                        out.toString())));
        return out;
    }

    private static Path picture(Path dir, String name, Color colour) throws IOException {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        Path png = dir.resolve(name);
        javax.imageio.ImageIO.write(img, "png", png.toFile());
        return png;
    }

    @Test
    void aTallVideoWithMovingDissolvingPicturesAStripAndALogo(@TempDir Path dir) throws IOException {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path audio = recording(dir, 0.1, 5.8);
        Spec spec = new Spec(
                "audio-uploads/a.m4a",
                null,
                "#111827",
                "360p",
                "WAVES",
                null,
                false,
                null,
                false,
                false,
                List.of(new Slide("overlay-uploads/a.png", 0), new Slide("overlay-uploads/b.png", 3000)),
                "TALL",
                true,
                true,
                "Episode 4 · Dara",
                "overlay-uploads/logo.png");
        Size frame = AudioToVideoRules.size(spec.resolution(), spec.shape());
        Path strip = dir.resolve("strip.png");
        TextRenderer.write(
                TextRenderer.render(
                        new OverlayRules.Layer(
                                "TEXT",
                                "Episode 4 · Dara",
                                "SansSerif",
                                600,
                                4.5,
                                "#ffffff",
                                "#000000",
                                0.55,
                                "LEFT",
                                null,
                                0.0,
                                0.5,
                                0.5,
                                1.0,
                                0L,
                                null,
                                "NONE"),
                        frame.h()),
                strip);
        Path out = media.audioToVideo(
                spec,
                audio,
                List.of(picture(dir, "a.png", Color.RED), picture(dir, "b.png", Color.BLUE)),
                null,
                strip,
                picture(dir, "logo.png", Color.WHITE),
                frame,
                6000,
                ctx);
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertEquals(360, p.width());
        assertEquals(640, p.height());
        assertTrue(p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 6000) < 400, "expected about 6 s, got " + p.durationMs());
    }

    @Test
    void silenceIsTrimmedAndAPartCanBeCutOut(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path audio = recording(dir, 2, 3);
        long trimmed = media.probe(
                        media.prepareAudio(audio, null, null, true, ctx).toString(), dir)
                .durationMs();
        assertTrue(trimmed > 3000 && trimmed < 4000, "expected the 3 s of sound and a little, got " + trimmed);
        long part = media.probe(
                        media.prepareAudio(audio, 1000L, 4000L, false, ctx).toString(), dir)
                .durationMs();
        assertTrue(Math.abs(part - 3000) < 200, "expected about 3 s, got " + part);
        assertTrue(media.prepareAudio(audio, null, null, false, ctx) == audio, "nothing asked, nothing done");
    }

    @Test
    void chaptersAreFoundAtThePauses(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        Path audio = recording(dir, 2, 3);
        List<AudioToVideoRules.Silence> silences = media.silences(audio, dir);
        assertEquals(2, silences.size(), String.valueOf(silences));
        // 7 s with "parts" of 3 s: the cut lands in the middle of the pause nearest 3 s, the trailing one.
        List<AudioToVideoRules.Range> parts = AudioToVideoRules.chapterRanges(silences, 7000, 3000);
        assertTrue(parts.size() >= 2, String.valueOf(parts));
        assertEquals(7000, parts.get(parts.size() - 1).endMs());
    }

    @Test
    void aTallWaveformRendersAlongTheBottomAndInTheMiddle(@TempDir Path dir) {
        assumeTrue(ffmpeg, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path audio = recording(dir, 0.1, 2.8);
        for (String[] style : new String[][] {{"WAVES", "60"}, {"PULSE", "75"}}) {
            Spec spec = new Spec(
                    "audio-uploads/a.m4a",
                    null,
                    "#111827",
                    "360p",
                    style[0],
                    null,
                    false,
                    null,
                    false,
                    false,
                    List.of(),
                    null,
                    false,
                    false,
                    null,
                    null,
                    Integer.parseInt(style[1]));
            Path out = media.audioToVideo(spec, audio, List.of(), null, AudioToVideoRules.size("360p"), 3000, ctx);
            MediaTools.Probe p = media.probe(out.toString(), dir);
            assertEquals(640, p.width(), style[0]);
            assertTrue(Math.abs(p.durationMs() - 3000) < 400, style[0] + ": got " + p.durationMs());
        }
    }
}
