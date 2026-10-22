package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.example.videolingo.pipeline.AudioToVideoRules.Chapter;
import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.AudioToVideoRules.Slide;
import com.example.videolingo.pipeline.AudioToVideoRules.Spec;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Video from audio, the later options: a blurred backdrop, a progress bar, chapter titles, karaoke and
// two-language subtitles, the highlight clip and music under the recording. Rules, then real renders
// (skipped where ffmpeg isn't installed).
class AudioToVideoMoreTest {

    private static Spec spec(boolean blur, boolean progress, List<Chapter> chapters) {
        return new Spec(
                "audio-uploads/a.m4a",
                null,
                "#111827",
                "360p",
                null,
                null,
                false,
                null,
                false,
                false,
                List.of(new Slide("overlay-uploads/a.png", 0)),
                "TALL",
                false,
                false,
                null,
                null,
                null,
                blur,
                progress,
                chapters);
    }

    // ── Rules ──

    @Test
    void chaptersAreTitledInOrderAndApart() {
        assertNull(AudioToVideoRules.validateChapters(List.of(new Chapter(0, "Intro"), new Chapter(60_000, "Verbs"))));
        assertNotNull(AudioToVideoRules.validateChapters(List.of(new Chapter(0, "A"), new Chapter(3000, "B"))));
        assertNotNull(AudioToVideoRules.validateChapters(List.of(new Chapter(0, " "))));
        assertNotNull(AudioToVideoRules.validateChapters(List.of(new Chapter(0, "x".repeat(81)))));
        assertEquals(
                1,
                AudioToVideoRules.usableChapters(List.of(new Chapter(0, "A"), new Chapter(9_500, "B")), 10_000)
                        .size());
        assertTrue(AudioToVideoRules.describe(spec(true, true, List.of(new Chapter(0, "A"))))
                .endsWith("blurred backdrop, progress bar, 1 chapter)"));
    }

    @Test
    void theGraphShowsEachChapterForAWhileAndSlidesTheBarAlong() {
        Spec s = spec(true, true, List.of(new Chapter(0, "Intro"), new Chapter(6000, "Verbs")));
        String g = AudioToVideoRules.filterGraph(
                s,
                AudioToVideoRules.size("360p", "TALL"),
                1,
                false,
                List.of(10_000L),
                false,
                false,
                s.chapters(),
                10_000);
        assertTrue(g.contains("[2:v]overlay=14:32:format=auto:enable='between(t,0.000,4.000)'[ch0]"), g);
        assertTrue(g.contains("[3:v]overlay=14:32:format=auto:enable='between(t,6.000,10.000)'[ch1]"), g);
        assertTrue(g.contains("overlay=x='-w+w*t/10.000':y=H-h:eval=frame"), g);
        assertTrue(AudioToVideoRules.backdropGraph(new Size(360, 640)).contains("gblur=sigma=21"));
    }

    @Test
    void karaokeFillsEachWordOverItsOwnTime() {
        CaptionRules.Cue timed = new CaptionRules.Cue(
                1000,
                3000,
                "Hello big world",
                null,
                List.of(
                        new CaptionRules.Word(1200, 1500, "Hello"),
                        new CaptionRules.Word(1500, 2000, "big"),
                        new CaptionRules.Word(2100, 2900, "world")));
        assertEquals("{\\k20}{\\kf30}Hello {\\kf60}big {\\kf90}world", CaptionRules.karaokeText(timed));
        // Without word timings, the words share the line by their length.
        assertEquals("{\\kf50}ab {\\kf50}cd", CaptionRules.karaokeText(new CaptionRules.Cue(0, 1000, "ab cd", null)));
        String ass = CaptionRules.toAss(List.of(timed), "KARAOKE", "BOTTOM", 5, 1280, 720);
        assertTrue(ass.contains("Style: Main,Noto Sans,36,&H0000D7FF,&H00FFFFFF,"), ass);
        assertTrue(ass.contains(",Main,,0,0,0,,{\\k20}{\\kf30}Hello"), ass);
        assertTrue(OverlayRules.CAPTION_STYLES.contains("KARAOKE"));
    }

    @Test
    void theHighlightIsTheLoudestStretchStartingAfterAPause() {
        List<Double> levels = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            levels.add(i >= 30 && i < 45 ? -20.0 : -60.0);
        }
        HighlightRules.Range plain = HighlightRules.best(levels, List.of(), 60_000, 15_000);
        assertEquals(30_000, plain.startMs());
        assertEquals(45_000, plain.endMs());
        HighlightRules.Range snapped =
                HighlightRules.best(levels, List.of(new AudioToVideoRules.Silence(28_000, 29_500)), 60_000, 15_000);
        assertEquals(29_500, snapped.startMs());
        assertEquals(new HighlightRules.Range(0, 5000), HighlightRules.best(levels, List.of(), 5000, 15_000));
        assertEquals(
                List.of(-23.5, Double.NEGATIVE_INFINITY),
                HighlightRules.parseLevels(
                        "lavfi.astats.Overall.RMS_level=-23.5\nframe:1\nlavfi.astats.Overall.RMS_level=-inf\n"));
    }

    // ── Real renders ──

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

    private static Path tone(Path dir, String name, String source) {
        Path out = dir.resolve(name);
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
                        source,
                        "-c:a",
                        "aac",
                        out.toString())));
        return out;
    }

    private static BufferedImage frameAt(Path video, Path dir, double seconds) throws IOException {
        Path png = dir.resolve("frame-" + seconds + ".png");
        assertEquals(
                0,
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-ss",
                        String.valueOf(seconds),
                        "-i",
                        video.toString(),
                        "-frames:v",
                        "1",
                        png.toString())));
        return javax.imageio.ImageIO.read(png.toFile());
    }

    @Test
    void aBlurredBackdropAProgressBarAndChapterTitlesRender(@TempDir Path dir) throws IOException {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path audio = tone(dir, "a.m4a", "sine=frequency=440:duration=6");
        // A red landscape picture on a tall frame: without the backdrop the top would be the dark background.
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        Path cover = dir.resolve("cover.png");
        javax.imageio.ImageIO.write(img, "png", cover.toFile());

        Spec s = spec(true, true, List.of(new Chapter(0, "Intro"), new Chapter(5000, "Late")));
        Size frame = AudioToVideoRules.size("360p", "TALL");
        Path backed = media.still(cover, AudioToVideoRules.backdropGraph(frame), "backdrop", ctx);
        List<Path> titles = new ArrayList<>();
        for (Chapter c : AudioToVideoRules.usableChapters(s.chapters(), 6000)) {
            Path png = dir.resolve("ch-" + titles.size() + ".png");
            TextRenderer.write(
                    TextRenderer.render(
                            new OverlayRules.Layer(
                                    "TEXT",
                                    c.title(),
                                    "SansSerif",
                                    700,
                                    5.5,
                                    "#ffffff",
                                    "#000000",
                                    0.6,
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
                    png);
            titles.add(png);
        }
        Path out = media.audioToVideo(s, audio, List.of(backed), null, null, null, titles, frame, 6000, ctx);
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertEquals(360, p.width());
        assertEquals(640, p.height());
        assertTrue(Math.abs(p.durationMs() - 6000) < 400, "got " + p.durationMs());

        BufferedImage early = frameAt(out, dir, 0.5);
        BufferedImage late = frameAt(out, dir, 5.5);
        Color top = new Color(early.getRGB(180, 100));
        assertTrue(top.getRed() > 100 && top.getBlue() < 90, "the backdrop is the blurred red picture, got " + top);
        // The bar fills from the left: near the right end it has only reached there late on.
        Color barEarly = new Color(early.getRGB(330, 638));
        Color barLate = new Color(late.getRGB(330, 638));
        assertTrue(barLate.getGreen() > barEarly.getGreen() + 80, "early " + barEarly + ", late " + barLate);
    }

    private static Spec waveSpec(String color2, boolean glow) {
        return new Spec(
                "audio-uploads/a.m4a",
                null,
                "#111827",
                "360p",
                "WAVES",
                "#ff5028",
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
                80,
                false,
                false,
                List.of(),
                color2,
                glow);
    }

    @Test
    void theWaveformCanBeShadedAcrossAndGlow() {
        assertEquals(
                "geq=r='255+(-255)*X/W':g='80+(120)*X/W':b='40+(215)*X/W':a='alpha(X,Y)'",
                AudioToVideoRules.gradientFilter("#ff5028", "#00c8ff"));
        String g = AudioToVideoRules.filterGraph(waveSpec("#00c8ff", true), AudioToVideoRules.size("360p"), 0, false);
        assertTrue(g.contains("[wv]geq="), g);
        assertTrue(g.contains("[wvg]split[wva][wvb];[wvb]gblur=sigma=6,colorchannelmixer=aa=2[wvl]"), g);
        assertTrue(g.contains("[pic][wvo]overlay="), g);
        assertNotNull(AudioToVideoRules.validate(waveSpec("blue", false)));
        assertTrue(AudioToVideoRules.describe(waveSpec("#00c8ff", true)).contains("gradient, glow"));
    }

    @Test
    void aShadedGlowingWaveformRenders(@TempDir Path dir) throws IOException {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        MediaTools media = media();
        // A slow, loud tone fills the band, so its colour can be read at both ends.
        Path audio = tone(dir, "a.m4a", "sine=frequency=2:duration=3,volume=10");
        Path out = media.audioToVideo(
                waveSpec("#00c8ff", true),
                audio,
                List.of(),
                null,
                AudioToVideoRules.size("360p"),
                3000,
                new JobContext(mock(JobStore.class), 1, dir));
        BufferedImage f = frameAt(out, dir, 1.5);
        int y = f.getHeight() - (int) (f.getHeight() * 0.05) - (int) (f.getHeight() * 0.4);
        Color left = brightestIn(f, 10, y);
        Color right = brightestIn(f, f.getWidth() - 10, y);
        assertTrue(left.getRed() > left.getBlue(), "left is red-ish: " + left);
        assertTrue(right.getBlue() > right.getRed(), "right is blue-ish: " + right);
    }

    /** The brightest pixel in the column at `x`, from `fromY` down: somewhere on the waveform. */
    private static Color brightestIn(BufferedImage img, int x, int fromY) {
        Color best = new Color(0, 0, 0);
        for (int y = Math.max(0, fromY); y < img.getHeight(); y++) {
            Color c = new Color(img.getRGB(x, y));
            if (c.getRed() + c.getGreen() + c.getBlue() > best.getRed() + best.getGreen() + best.getBlue()) {
                best = c;
            }
        }
        return best;
    }

    @Test
    void musicIsMixedUnderARecordingAndTheLevelsAreRead(@TempDir Path dir) {
        assumeTrue(run(List.of("ffmpeg", "-version")) == 0, "ffmpeg isn't installed");
        MediaTools media = media();
        JobContext ctx = new JobContext(mock(JobStore.class), 1, dir);
        Path speech = tone(dir, "speech.m4a", "sine=frequency=300:duration=4");
        Path music = tone(dir, "music.m4a", "sine=frequency=800:duration=1");
        AudioEditRules.Spec mix = new AudioEditRules.Spec(
                null,
                List.of(),
                List.of(),
                1,
                0,
                0,
                false,
                "OFF",
                false,
                1,
                0,
                0,
                "KEEP",
                new AudioEditRules.Music("audio-uploads/m.m4a", 0.2, true, true, 0));
        Path out = media.editAudio(
                speech.toString(),
                null,
                List.of(new MediaTools.Bed(music, true)),
                AudioEditRules.build(mix, new AudioEditRules.Inputs(4000, true, false, 4000L)),
                4000,
                ctx);
        MediaTools.Probe p = media.probe(out.toString(), dir);
        assertTrue(p.hasAudio());
        assertTrue(Math.abs(p.durationMs() - 4000) < 300, "got " + p.durationMs());

        // 1 s quiet, 2 s loud, 1 s quiet.
        Path rec = dir.resolve("rec.m4a");
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
                        "anullsrc=r=44100:cl=mono:d=1",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=2",
                        "-f",
                        "lavfi",
                        "-i",
                        "anullsrc=r=44100:cl=mono:d=1",
                        "-filter_complex",
                        "[0:a][1:a][2:a]concat=n=3:v=0:a=1",
                        "-c:a",
                        "aac",
                        rec.toString())));
        List<Double> levels = media.levels(rec, dir);
        assertTrue(levels.size() >= 4, "got " + levels);
        assertTrue(levels.get(0) < -60 && levels.get(1) > -30 && levels.get(2) > -30, "got " + levels);
    }
}
