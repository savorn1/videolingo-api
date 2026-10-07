package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.pipeline.AudioToVideoRules.Size;
import com.example.videolingo.pipeline.AudioToVideoRules.Spec;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AudioToVideoRulesTest {

    private static final String AUDIO = AudioEditRules.UPLOAD_PREFIX + "abc.mp3";
    private static final String COVER = OverlayRules.UPLOAD_PREFIX + "cover.png";

    private static Spec plain() {
        return new Spec(AUDIO, null, "#000000", null);
    }

    private static Spec styled(String cover, String waveform, boolean titleCard, boolean normalize, boolean denoise) {
        return new Spec(
                AUDIO,
                cover,
                "#102030",
                "720p",
                waveform,
                null,
                titleCard,
                titleCard ? "My title" : null,
                normalize,
                denoise);
    }

    private static String line(List<String> cmd) {
        return String.join(" ", cmd);
    }

    // ── validation ────────────────────────────────────────────────────────

    @Test
    void aValidRequestPasses() {
        assertNull(AudioToVideoRules.validate(new Spec(AUDIO, COVER, "#112233", "1080p")));
        assertNull(AudioToVideoRules.validate(new Spec(AUDIO, null, null, null)));
        assertNull(AudioToVideoRules.validate(styled(null, "BARS", true, true, true)));
    }

    @Test
    void theAudioMustBeAnUploadedAudioFile() {
        assertNotNull(AudioToVideoRules.validate(null));
        assertNotNull(AudioToVideoRules.validate(new Spec(null, null, null, null)));
        assertNotNull(AudioToVideoRules.validate(new Spec("videos/x.mp4", null, null, null)));
        assertNotNull(
                AudioToVideoRules.validate(new Spec(AudioEditRules.UPLOAD_PREFIX + "../secret.mp3", null, null, null)));
    }

    @Test
    void theCoverMustBeAnUploadedImage() {
        assertNotNull(AudioToVideoRules.validate(new Spec(AUDIO, "thumbnails/x.png", null, null)));
        assertNotNull(AudioToVideoRules.validate(new Spec(AUDIO, AudioEditRules.UPLOAD_PREFIX + "x.mp3", null, null)));
    }

    @Test
    void colourAndSizeAreChecked() {
        assertNotNull(AudioToVideoRules.validate(new Spec(AUDIO, null, "red", null)));
        assertNotNull(AudioToVideoRules.validate(new Spec(AUDIO, null, "#12345", null)));
        assertNotNull(AudioToVideoRules.validate(new Spec(AUDIO, null, "#12345g", null)));
        assertNotNull(AudioToVideoRules.validate(new Spec(AUDIO, null, null, "4k")));
    }

    @Test
    void waveformAndTitleOptionsAreChecked() {
        assertNotNull(AudioToVideoRules.validate(
                new Spec(AUDIO, null, null, null, "SPIRAL", null, false, null, false, false)));
        assertNotNull(AudioToVideoRules.validate(
                new Spec(AUDIO, null, null, null, "WAVES", "white", false, null, false, false)));
        assertNull(
                AudioToVideoRules.validate(new Spec(AUDIO, null, null, null, "NONE", null, false, null, false, false)));
        assertNotNull(
                AudioToVideoRules.validate(new Spec(AUDIO, null, null, null, null, null, true, "  ", false, false)));
        assertNotNull(
                AudioToVideoRules.validate(new Spec(AUDIO, null, null, null, null, null, true, null, false, false)));
        assertNotNull(AudioToVideoRules.validate(new Spec(
                AUDIO, null, null, null, null, null, true, "x".repeat(AudioToVideoRules.MAX_TITLE + 1), false, false)));
    }

    // ── sizes and colours ─────────────────────────────────────────────────

    @Test
    void sizesAreSixteenByNineWithEvenSides() {
        for (String r : AudioToVideoRules.RESOLUTIONS) {
            Size s = AudioToVideoRules.size(r);
            assertEquals(0, s.w() % 2);
            assertEquals(0, s.h() % 2);
            assertTrue(Math.abs(s.w() / (double) s.h() - 16.0 / 9) < 0.01, r);
            assertEquals(0, AudioToVideoRules.waveHeight(s) % 2, r);
        }
        assertEquals(new Size(1280, 720), AudioToVideoRules.size(null));
        assertEquals(new Size(1920, 1080), AudioToVideoRules.size("1080p"));
    }

    @Test
    void colourIsGivenToFfmpegInItsOwnForm() {
        assertEquals("0x1a2b3c", AudioToVideoRules.ffmpegColor("#1A2B3C"));
        assertEquals("0x111827", AudioToVideoRules.ffmpegColor(null));
        assertEquals("0x111827", AudioToVideoRules.ffmpegColor("nonsense"));
    }

    @Test
    void textAndWavesContrastWithTheBackground() {
        assertEquals("#ffffff", AudioToVideoRules.contrastColor("#111827"));
        assertEquals("#ffffff", AudioToVideoRules.contrastColor("#1e3a8a"));
        assertEquals("#111111", AudioToVideoRules.contrastColor("#ffffff"));
        assertEquals("#111111", AudioToVideoRules.contrastColor("#f5f5dc"));
        assertEquals("#ffffff", AudioToVideoRules.contrastColor(null));
    }

    // ── the title ─────────────────────────────────────────────────────────

    @Test
    void aTitleIsWrappedAtSpaces() {
        assertEquals(
                List.of("Learning English", "with stories"),
                AudioToVideoRules.wrapTitle("Learning English with stories", 16, 4));
        assertEquals(List.of("Short"), AudioToVideoRules.wrapTitle("  Short  ", 20, 4));
        assertEquals(List.of("a b c"), AudioToVideoRules.wrapTitle("a  b   c", 20, 4));
    }

    @Test
    void aLongWordIsSplitInsteadOfOverflowing() {
        assertEquals(List.of("abcdefgh", "ijkl"), AudioToVideoRules.wrapTitle("abcdefghijkl", 8, 4));
    }

    @Test
    void tooManyLinesEndWithAnEllipsis() {
        List<String> lines = AudioToVideoRules.wrapTitle("one two three four five six seven eight", 9, 2);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("…"));
    }

    @Test
    void nothingToWrapGivesNoLines() {
        assertTrue(AudioToVideoRules.wrapTitle(null, 10, 3).isEmpty());
        assertTrue(AudioToVideoRules.wrapTitle("   ", 10, 3).isEmpty());
        assertTrue(AudioToVideoRules.wrapTitle("abc", 0, 3).isEmpty());
    }

    @Test
    void theTitleIsOnlyDrawnWithoutACover() {
        assertTrue(styled(null, null, true, false, false).drawsTitle());
        assertFalse(styled(COVER, null, true, false, false).drawsTitle());
        assertFalse(styled(null, null, false, false, false).drawsTitle());
    }

    // ── the command ───────────────────────────────────────────────────────

    @Test
    void withACoverThePictureIsFittedOntoTheBackground() {
        List<String> cmd = AudioToVideoRules.command(
                "ffmpeg",
                styled(COVER, null, false, false, false),
                Path.of("a.mp3"),
                List.of(Path.of("c.png")),
                null,
                new Size(1280, 720),
                12_345,
                Path.of("out.mp4"));
        String line = line(cmd);
        assertTrue(line.contains("-loop 1 -framerate 5 -t 12.345 -i c.png -i a.mp3"));
        assertTrue(line.contains(
                "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=0x102030"));
        assertTrue(line.contains("-map [v] -map [a]"));
        assertTrue(line.contains("-tune stillimage"));
        assertTrue(line.contains("-t 12.345"));
        assertEquals("out.mp4", cmd.get(cmd.size() - 1));
        assertFalse(line.contains("lavfi"));
        assertFalse(line.contains("showwaves"));
    }

    @Test
    void withoutACoverTheBackgroundColourIsTheWholePicture() {
        String line = line(AudioToVideoRules.command(
                "ffmpeg", plain(), Path.of("a.mp3"), List.of(), null, new Size(640, 360), 5_000, Path.of("out.mp4")));
        assertTrue(line.contains("-f lavfi -i color=c=0x000000:s=640x360:r=5"));
        assertTrue(line.contains("[0:v]setsar=1,format=yuv420p[pic]"));
        assertTrue(line.contains("-t 5.000"));
        assertFalse(line.contains("-loop"));
    }

    @Test
    void aTitleImageIsOverlaidInTheMiddle() {
        String line = line(AudioToVideoRules.command(
                "ffmpeg",
                styled(null, null, true, false, false),
                Path.of("a.mp3"),
                List.of(),
                Path.of("t.png"),
                new Size(640, 360),
                5_000,
                Path.of("out.mp4")));
        assertTrue(line.contains("-i t.png"));
        assertTrue(line.contains("[pic][2:v]overlay=(W-w)/2:(H-h)/2:format=auto[titled]"));
        assertTrue(line.contains("[titled]null[v]"));
    }

    @Test
    void aWaveformIsDrawnAlongTheBottomAndRaisesTheFrameRate() {
        String line = line(AudioToVideoRules.command(
                "ffmpeg",
                styled(null, "WAVES", false, false, false),
                Path.of("a.mp3"),
                List.of(),
                null,
                new Size(1280, 720),
                5_000,
                Path.of("out.mp4")));
        assertTrue(line.contains("[1:a]anull,asplit=2[a][aw]"));
        assertTrue(line.contains("showwaves=s=1280x180:mode=cline:colors=0xffffff:rate=15"));
        assertTrue(line.contains("[pic][wv]overlay=0:H-h-36:format=auto,format=yuv420p[v]"));
        assertTrue(line.contains("-r 15"));
        assertFalse(line.contains("-tune stillimage"));
    }

    @Test
    void barsUseFrequencyBarsWithTheBlackKeyedOut() {
        String line = line(AudioToVideoRules.command(
                "ffmpeg",
                styled(null, "BARS", false, false, false),
                Path.of("a.mp3"),
                List.of(),
                null,
                new Size(1280, 720),
                5_000,
                Path.of("out.mp4")));
        assertTrue(line.contains("showfreqs=s=1280x180:mode=bar"));
        assertTrue(line.contains("colorkey=0x000000"));
    }

    @Test
    void theWaveColourFollowsTheBackgroundUnlessGiven() {
        Spec light = new Spec(AUDIO, null, "#ffffff", null, "WAVES", null, false, null, false, false);
        assertTrue(line(AudioToVideoRules.command(
                        "ffmpeg", light, Path.of("a.mp3"), List.of(), null, new Size(640, 360), 1000, Path.of("o.mp4")))
                .contains("colors=0x111111"));
        Spec red = new Spec(AUDIO, null, "#ffffff", null, "WAVES", "#FF0000", false, null, false, false);
        assertTrue(line(AudioToVideoRules.command(
                        "ffmpeg", red, Path.of("a.mp3"), List.of(), null, new Size(640, 360), 1000, Path.of("o.mp4")))
                .contains("colors=0xff0000"));
    }

    @Test
    void loudnessAndNoiseFiltersRunBeforeAnythingElseAndTheRateIsRestored() {
        String line = line(AudioToVideoRules.command(
                "ffmpeg",
                styled(null, null, false, true, true),
                Path.of("a.mp3"),
                List.of(),
                null,
                new Size(640, 360),
                5_000,
                Path.of("o.mp4")));
        assertTrue(line.contains("[1:a]afftdn=nf=-25,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100[a]"));
    }

    @Test
    void theWaveformSeesTheCleanedSound() {
        String graph =
                AudioToVideoRules.filterGraph(styled(null, "WAVES", false, true, false), new Size(640, 360), 0, false);
        assertTrue(graph.contains("[1:a]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100,asplit=2[a][aw]"));
    }

    @Test
    void theLengthUsesADotWhateverTheServerLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String line = line(AudioToVideoRules.command(
                    "ffmpeg", plain(), Path.of("a.mp3"), List.of(), null, new Size(640, 360), 1_500, Path.of("o.mp4")));
            assertTrue(line.contains("-t 1.500"));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void theDescriptionSaysWhatWillBeMade() {
        assertEquals(
                "Audio to video (1280×720, plain background)",
                AudioToVideoRules.describe(new Spec(AUDIO, null, null, null)));
        assertEquals(
                "Audio to video (1920×1080, cover picture)",
                AudioToVideoRules.describe(new Spec(AUDIO, COVER, null, "1080p")));
        assertEquals(
                "Audio to video (1280×720, title card, waveform, normalized, noise reduced)",
                AudioToVideoRules.describe(styled(null, "WAVES", true, true, true)));
        assertEquals(
                "Audio to video (1280×720, plain background, bars)",
                AudioToVideoRules.describe(styled(null, "BARS", false, false, false)));
    }

    // ── the slideshow ─────────────────────────────────────────────────────

    private static Spec slideshow(long... starts) {
        List<AudioToVideoRules.Slide> slides = new java.util.ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            slides.add(new AudioToVideoRules.Slide(OverlayRules.UPLOAD_PREFIX + "p" + i + ".png", starts[i]));
        }
        return new Spec(AUDIO, null, "#102030", "720p", null, null, false, null, false, false, slides);
    }

    @Test
    void aLoneCoverIsOneSlideFromTheStart() {
        Spec spec = new Spec(AUDIO, COVER, null, null);
        assertEquals(List.of(new AudioToVideoRules.Slide(COVER, 0)), spec.slides());
        Spec show = slideshow(0, 5_000);
        assertEquals(OverlayRules.UPLOAD_PREFIX + "p0.png", show.coverKey());
    }

    @Test
    void slidesMustStartAtZeroAndAdvanceByAtLeastASecond() {
        assertNull(AudioToVideoRules.validate(slideshow(0, 1_000, 60_000)));
        assertNotNull(AudioToVideoRules.validate(slideshow(500, 5_000)));
        assertNotNull(AudioToVideoRules.validate(slideshow(0, 5_000, 5_500)));
        assertNotNull(AudioToVideoRules.validate(slideshow(0, 5_000, 5_000)));
        assertNotNull(AudioToVideoRules.validate(slideshow(0, 5_000, 4_000)));
    }

    @Test
    void everySlideMustBeAnUploadedImageAndThereIsALimit() {
        Spec bad = new Spec(
                AUDIO,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                false,
                false,
                List.of(
                        new AudioToVideoRules.Slide(OverlayRules.UPLOAD_PREFIX + "a.png", 0),
                        new AudioToVideoRules.Slide("thumbnails/x.png", 5_000)));
        assertNotNull(AudioToVideoRules.validate(bad));
        long[] many = new long[AudioToVideoRules.MAX_SLIDES + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = i * 2_000L;
        }
        assertNotNull(AudioToVideoRules.validate(slideshow(many)));
        assertNull(AudioToVideoRules.validate(slideshow(java.util.Arrays.copyOf(many, AudioToVideoRules.MAX_SLIDES))));
    }

    @Test
    void aTitleIsNotNeededOnceThereArePictures() {
        Spec spec = new Spec(
                AUDIO,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                false,
                false,
                List.of(new AudioToVideoRules.Slide(COVER, 0)));
        assertNull(AudioToVideoRules.validate(spec));
        assertFalse(spec.drawsTitle());
    }

    @Test
    void usableSlidesKeepThoseThatStartBeforeTheLastHalfSecond() {
        List<AudioToVideoRules.Slide> s =
                slideshow(0, 5_000, 9_400, 9_600, 12_000).slides();
        assertEquals(
                List.of(0L, 5_000L, 9_400L),
                AudioToVideoRules.usableSlides(s, 10_000).stream()
                        .map(AudioToVideoRules.Slide::startMs)
                        .toList());
        assertEquals(
                1, AudioToVideoRules.usableSlides(slideshow(0).slides(), 100).size());
    }

    @Test
    void eachPictureStaysUntilTheNextOneStarts() {
        List<AudioToVideoRules.Slide> s = slideshow(0, 4_000, 9_000).slides();
        assertEquals(List.of(4_000L, 5_000L, 3_000L), AudioToVideoRules.slideDurations(s, 12_000));
    }

    @Test
    void theCommandRunsThePicturesOneAfterAnotherThenTheSound() {
        Spec spec = slideshow(0, 4_000, 9_000);
        List<String> cmd = AudioToVideoRules.command(
                "ffmpeg",
                spec,
                Path.of("a.mp3"),
                List.of(Path.of("p0.png"), Path.of("p1.png"), Path.of("p2.png")),
                null,
                new Size(1280, 720),
                12_000,
                Path.of("out.mp4"));
        String line = line(cmd);
        assertTrue(
                line.contains(
                        "-loop 1 -framerate 5 -t 4.000 -i p0.png -loop 1 -framerate 5 -t 5.000 -i p1.png -loop 1 -framerate 5 -t 3.000 -i p2.png -i a.mp3"));
        assertTrue(line.contains("[2:v]scale=1280:720"));
        assertTrue(line.contains("[s0][s1][s2]concat=n=3:v=1:a=0[pic]"));
        assertTrue(line.contains("[3:a]anull[a]"));
    }

    @Test
    void theSoundAndTitleInputsMoveUpBehindThePictures() {
        Spec spec = new Spec(
                AUDIO,
                null,
                "#102030",
                "720p",
                "WAVES",
                null,
                false,
                null,
                false,
                false,
                slideshow(0, 4_000).slides());
        String graph = AudioToVideoRules.filterGraph(spec, new Size(640, 360), 2, false);
        assertTrue(graph.contains("[2:a]anull,asplit=2[a][aw]"));
        String withTitle =
                AudioToVideoRules.filterGraph(styled(null, null, true, false, false), new Size(640, 360), 0, true);
        assertTrue(withTitle.contains("[pic][1:v]") || withTitle.contains("[2:v]"));
    }

    @Test
    void oneSlideNeedsNoJoining() {
        String graph = AudioToVideoRules.filterGraph(slideshow(0), new Size(640, 360), 1, false);
        assertFalse(graph.contains("concat"));
        assertTrue(graph.contains("[pic]"));
    }

    @Test
    void thePictureFilesMustMatchThePicturesShown() {
        Spec spec = slideshow(0, 4_000);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> AudioToVideoRules.command(
                        "ffmpeg",
                        spec,
                        Path.of("a.mp3"),
                        List.of(Path.of("p0.png")),
                        null,
                        new Size(640, 360),
                        10_000,
                        Path.of("o.mp4")));
    }

    @Test
    void theDescriptionCountsPictures() {
        assertEquals("Audio to video (1280×720, 3 pictures)", AudioToVideoRules.describe(slideshow(0, 4_000, 9_000)));
    }

    @Test
    void everyWaveformStyleIsAcceptedAndDrawnItsOwnWay() {
        for (String style : AudioToVideoRules.WAVEFORMS) {
            assertNull(AudioToVideoRules.validate(styled(null, style, false, false, false)), style);
        }
        String spikes = AudioToVideoRules.filterGraph(
                styled(null, "SPIKES", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(spikes.contains("showwaves=s=1280x180:mode=line"));
        String dots =
                AudioToVideoRules.filterGraph(styled(null, "DOTS", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(dots.contains("showwaves=s=1280x180:mode=point"));
        String spectrum = AudioToVideoRules.filterGraph(
                styled(null, "SPECTRUM", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(spectrum.contains("showfreqs=s=1280x180:mode=line"));
        assertTrue(spectrum.contains("colorkey=0x000000"));
    }

    @Test
    void theStyleIsNamedInTheJobLog() {
        assertEquals("waveform", AudioToVideoRules.waveLabel("WAVES"));
        assertEquals("bars", AudioToVideoRules.waveLabel("BARS"));
        assertEquals("spikes", AudioToVideoRules.waveLabel("SPIKES"));
        assertEquals("dots", AudioToVideoRules.waveLabel("DOTS"));
        assertEquals("spectrum", AudioToVideoRules.waveLabel("SPECTRUM"));
    }

    @Test
    void pulseAndBlocksAreDrawnInTheMiddleAsSeparateBars() {
        String pulse = AudioToVideoRules.filterGraph(
                styled(null, "PULSE", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(pulse.contains("mode=cline:scale=sqrt"));
        assertTrue(pulse.contains("flags=neighbor"));
        assertTrue(pulse.contains("geq=r='r(X,Y)'"));
        assertTrue(pulse.contains("overlay=(W-w)/2:(H-h)/2"));
        String blocks = AudioToVideoRules.filterGraph(
                styled(null, "BLOCKS", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(blocks.contains("overlay=(W-w)/2:(H-h)/2"));
        // The other styles stay along the bottom.
        String waves = AudioToVideoRules.filterGraph(
                styled(null, "WAVES", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(waves.contains("overlay=0:H-h-36"));
    }

    @Test
    void barLayoutsUseWholePixelsAndStayInsideTheFrame() {
        for (Size size :
                new Size[] {new Size(640, 360), new Size(854, 480), new Size(1280, 720), new Size(1920, 1080)}) {
            for (String style : AudioToVideoRules.CUT_BAR_STYLES) {
                AudioToVideoRules.BarLayout l = AudioToVideoRules.barLayout(style, size);
                assertTrue(l.bar() >= 2 && l.bar() < l.pitch(), style + " " + size);
                assertTrue(
                        l.width() <= size.w() * 0.85 && l.width() >= size.w() * 0.7,
                        style + " " + size + " width " + l.width());
                assertEquals(0, l.width() % 2);
                assertEquals(0, l.height() % 2);
                assertTrue(l.height() <= size.h() / 2);
                assertTrue(l.bars() >= 8);
            }
        }
        AudioToVideoRules.BarLayout thin = AudioToVideoRules.barLayout("PULSE", new Size(1280, 720));
        AudioToVideoRules.BarLayout thick = AudioToVideoRules.barLayout("BLOCKS", new Size(1280, 720));
        assertTrue(thick.bar() > thin.bar() && thick.bars() < thin.bars());
    }

    @Test
    void barStylesDifferInCountAndThickness() {
        Size hd = new Size(1280, 720);
        AudioToVideoRules.BarLayout fine = AudioToVideoRules.barLayout("FINE", hd);
        AudioToVideoRules.BarLayout pulse = AudioToVideoRules.barLayout("PULSE", hd);
        AudioToVideoRules.BarLayout stripes = AudioToVideoRules.barLayout("STRIPES", hd);
        AudioToVideoRules.BarLayout blocks = AudioToVideoRules.barLayout("BLOCKS", hd);
        assertTrue(fine.bars() > pulse.bars() && pulse.bars() > stripes.bars() && stripes.bars() > blocks.bars());
        assertTrue(AudioToVideoRules.isCentered("FINE") && AudioToVideoRules.isCentered("STRIPES"));
        assertFalse(AudioToVideoRules.isCentered("BARS"));
    }

    @Test
    void reflectionIsCentredWithAFadedLowerHalf() {
        String g = AudioToVideoRules.filterGraph(
                styled(null, "REFLECT", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(AudioToVideoRules.isCentered("REFLECT"));
        assertTrue(g.contains("if(gt(Y,H/2),alpha(X,Y)*0.35,alpha(X,Y))"), g);
        assertTrue(g.contains("overlay=(W-w)/2:(H-h)/2"), g);
    }

    @Test
    void columnsStandOnTheBottomEdge() {
        Size hd = new Size(1280, 720);
        String g = AudioToVideoRules.filterGraph(styled(null, "COLUMNS", false, false, false), hd, 0, false);
        AudioToVideoRules.BarLayout lay = AudioToVideoRules.barLayout("COLUMNS", hd);
        assertFalse(AudioToVideoRules.isCentered("COLUMNS"));
        // A quarter of the frame tall by default, like the other bottom styles; drawn twice as tall and the top half
        // kept.
        assertEquals(180, lay.height());
        assertTrue(g.contains("x360:mode=cline"), g);
        assertTrue(g.contains("crop=" + lay.bars() + ":180:0:0"), g);
        assertTrue(g.contains("overlay=(W-w)/2:H-h-36"), g);
        assertEquals(504, AudioToVideoRules.barLayout("COLUMNS", hd, 70).height());
    }

    @Test
    void stereoDrawsOneBandPerChannel() {
        String g = AudioToVideoRules.filterGraph(
                styled(null, "STEREO", false, false, false), new Size(1280, 720), 0, false);
        assertTrue(g.contains("split_channels=1"), g);
        assertTrue(g.contains("overlay=0:H-h-36"), g);
        assertTrue(AudioToVideoRules.WAVEFORMS.containsAll(java.util.List.of("REFLECT", "COLUMNS", "STEREO")));
    }
}
