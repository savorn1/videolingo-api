package com.example.videolingo.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Turning an audio file (a podcast, a song, a lesson recording) into a video:
// the sound plus a picture — a cover image fitted onto a solid background, or
// the title written on the background, or just the colour — and optionally a
// moving waveform along the bottom. Pure rules and the ffmpeg command, so they
// can be checked without running ffmpeg; the job itself is PipelineSteps.audioToVideoJob.
public final class AudioToVideoRules {

    private AudioToVideoRules() {}

    /** One picture of the slideshow: shown from `startMs` until the next picture starts (or the sound ends). */
    public record Slide(String key, long startMs) {}

    /**
     * What to make. waveform: NONE, WAVES or BARS (null = NONE); waveColor null
     * picks white or black to suit the background. titleCard writes titleText
     * on the background when there is no picture. `slides` are the pictures with the
     * time each appears; a lone `coverKey` is the same as one slide from the start.
     */
    public record Spec(
            String audioKey,
            String coverKey,
            String background,
            String resolution,
            String waveform,
            String waveColor,
            boolean titleCard,
            String titleText,
            boolean normalize,
            boolean denoise,
            List<Slide> slides,
            String shape,
            boolean motion,
            boolean crossfade,
            String stripText,
            String logoKey,
            Integer waveHeightPct) {

        public Spec {
            slides = slides == null ? List.of() : List.copyOf(slides);
            if (slides.isEmpty() && coverKey != null) {
                slides = List.of(new Slide(coverKey, 0));
            } else if (coverKey == null && !slides.isEmpty()) {
                coverKey = slides.get(0).key();
            }
        }

        /** Wide, still pictures cut one to the next, no strip or logo. */
        public Spec(
                String audioKey,
                String coverKey,
                String background,
                String resolution,
                String waveform,
                String waveColor,
                boolean titleCard,
                String titleText,
                boolean normalize,
                boolean denoise,
                List<Slide> slides) {
            this(
                    audioKey,
                    coverKey,
                    background,
                    resolution,
                    waveform,
                    waveColor,
                    titleCard,
                    titleText,
                    normalize,
                    denoise,
                    slides,
                    null,
                    false,
                    false,
                    null,
                    null,
                    null);
        }

        /** With the shape, moving pictures, strip and logo; the waveform at its usual height. */
        public Spec(
                String audioKey,
                String coverKey,
                String background,
                String resolution,
                String waveform,
                String waveColor,
                boolean titleCard,
                String titleText,
                boolean normalize,
                boolean denoise,
                List<Slide> slides,
                String shape,
                boolean motion,
                boolean crossfade,
                String stripText,
                String logoKey) {
            this(
                    audioKey,
                    coverKey,
                    background,
                    resolution,
                    waveform,
                    waveColor,
                    titleCard,
                    titleText,
                    normalize,
                    denoise,
                    slides,
                    shape,
                    motion,
                    crossfade,
                    stripText,
                    logoKey,
                    null);
        }

        /** Without pictures: the sound, waveform, title and options, nothing on the picture side. */
        public Spec(
                String audioKey,
                String coverKey,
                String background,
                String resolution,
                String waveform,
                String waveColor,
                boolean titleCard,
                String titleText,
                boolean normalize,
                boolean denoise) {
            this(
                    audioKey,
                    coverKey,
                    background,
                    resolution,
                    waveform,
                    waveColor,
                    titleCard,
                    titleText,
                    normalize,
                    denoise,
                    List.of());
        }

        /** The plain form: a picture (or colour) and the sound, nothing else. */
        public Spec(String audioKey, String coverKey, String background, String resolution) {
            this(audioKey, coverKey, background, resolution, null, null, false, null, false, false, List.of());
        }

        /** Pictures that move (a slow zoom) or dissolve into each other need a smooth frame rate. */
        public boolean smoothPictures() {
            return !slides.isEmpty() && (motion || (crossfade && slides.size() > 1));
        }

        public boolean hasStrip() {
            return stripText != null && !stripText.isBlank();
        }

        public boolean hasWaveform() {
            return waveform != null && !waveform.equals("NONE");
        }

        /** The title is only drawn when there is no picture to show instead. */
        public boolean drawsTitle() {
            return titleCard && slides.isEmpty() && titleText != null && !titleText.isBlank();
        }
    }

    public record Size(int w, int h) {}

    /** Output sizes on offer, all 16:9 with even sides (TALL / SQUARE shapes are built from them). */
    public static final List<String> RESOLUTIONS = List.of("360p", "480p", "720p", "1080p");

    /** WIDE = 16:9, TALL = 9:16 (Shorts, Reels, TikTok), SQUARE = 1:1 — at the resolution's height as the short side. */
    public static final List<String> SHAPES = List.of("WIDE", "TALL", "SQUARE");

    /** How long one picture takes to dissolve into the next. */
    static final long CROSSFADE_MS = 800;

    /** How much a moving picture grows over its time on screen (6 %). */
    static final double ZOOM_AMOUNT = 0.06;

    public static final int MAX_STRIP = 120;

    public static final String DEFAULT_RESOLUTION = "720p";
    public static final String DEFAULT_BACKGROUND = "#111827";
    /**
     * WAVES = a centred line, SPIKES = vertical sticks, DOTS = a trail of points, BARS = frequency bars, SPECTRUM = a thin frequency line,
     * PULSE = thin evenly spaced bars, BLOCKS = thick ones, FINE = hair-thin dense ones and STRIPES = medium ones, all drawn mirrored around the middle of the picture;
     * REFLECT = bars in the middle whose lower half is a faded reflection, COLUMNS = bars rising from the bottom edge and STEREO = the left
     * and right channels as two bands along the bottom.
     */
    public static final List<String> WAVEFORMS = List.of(
            "NONE",
            "WAVES",
            "BARS",
            "SPIKES",
            "DOTS",
            "SPECTRUM",
            "PULSE",
            "BLOCKS",
            "FINE",
            "STRIPES",
            "REFLECT",
            "COLUMNS",
            "STEREO");

    public static final int MAX_TITLE = 200;
    public static final int MAX_SLIDES = 30;
    /** Pictures closer together than this would flash by. */
    public static final long MIN_SLIDE_MS = 1000;

    /** A still picture needs few frames, which keeps the file small; a moving waveform needs more. */
    static final int STILL_FPS = 5;

    static final int WAVE_FPS = 15;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** Null = valid; a message otherwise. */
    public static String validate(Spec spec) {
        if (spec == null) {
            return "Nothing to convert";
        }
        if (!AudioEditRules.isUploadKey(spec.audioKey())) {
            return "Upload the audio file first";
        }
        if (spec.slides().size() > MAX_SLIDES) {
            return "At most " + MAX_SLIDES + " pictures";
        }
        for (Slide slide : spec.slides()) {
            if (!OverlayRules.isUploadKey(slide.key())) {
                return "The cover picture must be an uploaded image";
            }
        }
        if (!spec.slides().isEmpty()) {
            if (spec.slides().get(0).startMs() != 0) {
                return "The first picture must start at 0:00";
            }
            for (int i = 1; i < spec.slides().size(); i++) {
                if (spec.slides().get(i).startMs() - spec.slides().get(i - 1).startMs() < MIN_SLIDE_MS) {
                    return "Each picture must start at least " + (MIN_SLIDE_MS / 1000)
                            + " second after the one before it";
                }
            }
        }
        if (spec.background() != null && !HEX_COLOR.matcher(spec.background()).matches()) {
            return "The background colour must look like #1a2b3c";
        }
        if (spec.resolution() != null && !RESOLUTIONS.contains(spec.resolution())) {
            return "Pick a size: " + String.join(", ", RESOLUTIONS);
        }
        if (spec.waveform() != null && !WAVEFORMS.contains(spec.waveform())) {
            return "Pick a waveform style: " + String.join(", ", WAVEFORMS);
        }
        if (spec.waveColor() != null && !HEX_COLOR.matcher(spec.waveColor()).matches()) {
            return "The waveform colour must look like #ffffff";
        }
        if (spec.titleCard()
                && spec.slides().isEmpty()
                && (spec.titleText() == null || spec.titleText().isBlank())) {
            return "Write the title to show on the picture";
        }
        if (spec.titleText() != null && spec.titleText().length() > MAX_TITLE) {
            return "The title on the picture can be at most " + MAX_TITLE + " characters";
        }
        if (spec.waveHeightPct() != null
                && (spec.waveHeightPct() < MIN_WAVE_HEIGHT_PCT || spec.waveHeightPct() > MAX_WAVE_HEIGHT_PCT)) {
            return "The waveform's height is between " + MIN_WAVE_HEIGHT_PCT + " % and " + MAX_WAVE_HEIGHT_PCT
                    + " % of the picture";
        }
        if (spec.shape() != null && !SHAPES.contains(spec.shape())) {
            return "Pick a shape: wide, tall or square";
        }
        if (spec.stripText() != null && spec.stripText().length() > MAX_STRIP) {
            return "The strip text can be at most " + MAX_STRIP + " characters";
        }
        if (spec.logoKey() != null && !OverlayRules.isUploadKey(spec.logoKey())) {
            return "The logo must be an uploaded image";
        }
        return null;
    }

    public static Size size(String resolution) {
        return switch (resolution == null ? DEFAULT_RESOLUTION : resolution) {
            case "360p" -> new Size(640, 360);
            case "480p" -> new Size(854, 480);
            case "1080p" -> new Size(1920, 1080);
            default -> new Size(1280, 720);
        };
    }

    /** The frame for a resolution and shape: TALL turns the wide frame on its side, SQUARE uses its height both ways. */
    public static Size size(String resolution, String shape) {
        Size wide = size(resolution);
        return switch (shape == null ? "WIDE" : shape) {
            case "TALL" -> new Size(wide.h(), wide.w());
            case "SQUARE" -> new Size(wide.h(), wide.h());
            default -> wide;
        };
    }

    /** "#1a2b3c" → "0x1a2b3c", the form ffmpeg reads; the default colour when none is given. */
    static String ffmpegColor(String background) {
        String hex = background == null || !HEX_COLOR.matcher(background).matches() ? DEFAULT_BACKGROUND : background;
        return "0x" + hex.substring(1).toLowerCase(Locale.ROOT);
    }

    /** White on a dark colour, near-black on a light one. */
    public static String contrastColor(String background) {
        String hex = background == null || !HEX_COLOR.matcher(background).matches() ? DEFAULT_BACKGROUND : background;
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double luminance = (0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255)) / 255;
        return luminance < 0.55 ? "#ffffff" : "#111111";
    }

    /** Frames a second: smooth for moving or dissolving pictures, enough for a waveform, few for a still picture. */
    static final int SMOOTH_FPS = 25;

    static int fps(Spec spec) {
        return spec.smoothPictures() ? SMOOTH_FPS : spec.hasWaveform() ? WAVE_FPS : STILL_FPS;
    }

    /** How long each picture input runs: with a dissolve, each but the last overlaps the next by CROSSFADE_MS. */
    static List<Long> inputLengths(Spec spec, List<Long> lengths) {
        if (!spec.crossfade() || lengths.size() < 2) {
            return lengths;
        }
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < lengths.size(); i++) {
            out.add(lengths.get(i) + (i < lengths.size() - 1 ? CROSSFADE_MS : 0));
        }
        return out;
    }

    /** Where the strip goes: at the top when a waveform runs along the bottom, otherwise near the bottom. */
    static boolean stripAtTop(Spec spec) {
        return spec.hasWaveform() && !isCentered(spec.waveform());
    }

    /** The styles of separate mirrored bars, drawn in the middle of the picture rather than along the bottom. */
    public static final List<String> BAR_STYLES = List.of("PULSE", "BLOCKS", "FINE", "STRIPES", "REFLECT");

    /** The styles cut into separate bars: the centred ones, and COLUMNS, which rise from the bottom. */
    public static final List<String> CUT_BAR_STYLES =
            List.of("PULSE", "BLOCKS", "FINE", "STRIPES", "REFLECT", "COLUMNS");

    public static boolean isCentered(String waveform) {
        return BAR_STYLES.contains(waveform);
    }

    /**
     * How the centred bar styles are laid out: `bars` columns, each `pitch` pixels apart and `bar` pixels wide, across `width`
     * pixels, `height` tall. Whole pixels, so every bar is as crisp as the next.
     */
    public record BarLayout(int bars, int pitch, int bar, int width, int height) {}

    public static BarLayout barLayout(String waveform, Size size) {
        return barLayout(waveform, size, null);
    }

    /** Same, `heightPct` of the frame tall (null = the usual 40 % in the middle, 25 % for COLUMNS along the bottom). */
    public static BarLayout barLayout(String waveform, Size size, Integer heightPct) {
        // How many bars span the picture, and how much of each bar's slot is filled.
        double count = switch (waveform) {
            case "BLOCKS" -> 36.0;
            case "STRIPES" -> 60.0;
            case "FINE" -> 150.0;
            case "REFLECT" -> 72.0;
            case "COLUMNS" -> 48.0;
            default -> 90.0;
        };
        double fill = switch (waveform) {
            case "BLOCKS" -> 0.6;
            case "STRIPES" -> 0.5;
            case "FINE" -> 0.35;
            case "REFLECT" -> 0.5;
            case "COLUMNS" -> 0.55;
            default -> 0.4;
        };
        int target = (int) Math.round(size.w() * 0.8);
        int pitch = Math.max("BLOCKS".equals(waveform) ? 12 : 4, (int) Math.round(target / count));
        int bars = Math.max(8, target / pitch);
        int bar = Math.max(2, (int) Math.round(pitch * fill));
        int height = Math.max(
                16,
                (int) Math.round(size.h() * (heightPct == null ? (isCentered(waveform) ? 40 : 25) : heightPct) / 100.0)
                        / 2
                        * 2);
        return new BarLayout(bars, pitch, bar, bars * pitch / 2 * 2, height);
    }

    /** Height of the waveform band: a quarter of the frame, kept even. */
    static int waveHeight(Size size) {
        return waveHeight(size, null);
    }

    /** The waveform's height as a share of the frame: 10–80 %; left out, 25 % along the bottom or 40 % in the middle. */
    public static final int MIN_WAVE_HEIGHT_PCT = 10;

    public static final int MAX_WAVE_HEIGHT_PCT = 80;

    /** Height of the waveform band: `heightPct` of the frame (null = a quarter), kept even. */
    static int waveHeight(Size size, Integer heightPct) {
        return heightPct == null
                ? (size.h() / 4 / 2) * 2
                : Math.max(16, (int) Math.round(size.h() * heightPct / 100.0) / 2 * 2);
    }

    /** The title broken into lines of at most `maxChars`, at spaces where it can be; long words are split. Capped at `maxLines`, ending "…". */
    public static List<String> wrapTitle(String text, int maxChars, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || maxChars < 1 || maxLines < 1) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            while (word.length() > maxChars) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, maxChars));
                word = word.substring(maxChars);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.size() > maxLines) {
            List<String> kept = new ArrayList<>(lines.subList(0, maxLines));
            String last = kept.get(maxLines - 1);
            kept.set(
                    maxLines - 1,
                    (last.length() >= maxChars ? last.substring(0, Math.max(0, maxChars - 1)) : last) + "…");
            return kept;
        }
        return lines;
    }

    /**
     * The pictures that appear before the sound ends: one starting in the last half
     * second (or after) would never be seen. The first is always kept.
     */
    public static List<Slide> usableSlides(List<Slide> slides, long totalMs) {
        List<Slide> out = new ArrayList<>();
        for (Slide slide : slides) {
            if (out.isEmpty() || slide.startMs() < totalMs - 500) {
                out.add(slide);
            }
        }
        return out;
    }

    /** How long each of these pictures is on screen: until the next starts, the last until `totalMs`. */
    static List<Long> slideDurations(List<Slide> usable, long totalMs) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            long end = i + 1 < usable.size() ? usable.get(i + 1).startMs() : totalMs;
            out.add(Math.max(1, end - usable.get(i).startMs()));
        }
        return out;
    }

    /**
     * The filter graph. Inputs: 0 = the picture (cover, or a colour), 1 = the
     * sound, 2 = the title text image when there is one. Ends in labels [v] and [a].
     */
    static String filterGraph(Spec spec, Size size, int pictureCount, boolean hasTitle) {
        return filterGraph(spec, size, pictureCount, hasTitle, List.of(), false, false);
    }

    /**
     * Same, knowing each picture's time on screen (`lengths`, for the zoom and the dissolves), and whether a strip
     * image and a logo come as inputs after the title.
     */
    static String filterGraph(
            Spec spec,
            Size size,
            int pictureCount,
            boolean hasTitle,
            List<Long> lengths,
            boolean hasStrip,
            boolean hasLogo) {
        String color = ffmpegColor(spec.background());
        List<String> parts = new ArrayList<>();

        // Picture: one input per slide (or a colour), each fitted onto the background, then run one after another
        int pictures = Math.max(1, pictureCount);
        int audioIn = pictures;
        int titleIn = pictures + 1;
        int stripIn = titleIn + (hasTitle ? 1 : 0);
        int logoIn = stripIn + (hasStrip ? 1 : 0);
        String current = "pic";
        List<Long> inputs = inputLengths(spec, lengths);
        if (pictureCount == 0) {
            parts.add("[0:v]setsar=1,format=yuv420p[" + current + "]");
        } else {
            for (int i = 0; i < pictureCount; i++) {
                String zoom = "";
                if (spec.motion() && i < inputs.size()) {
                    // Grows a little over its time on screen, cut back to the frame around the centre.
                    zoom = String.format(
                            Locale.ROOT,
                            ",scale=w='trunc(%d*(1+%.3f*t/%.3f)/2)*2':h=-2:eval=frame,crop=%d:%d",
                            size.w(),
                            ZOOM_AMOUNT,
                            Math.max(0.001, inputs.get(i) / 1000.0),
                            size.w(),
                            size.h());
                }
                parts.add(
                        "[" + i + ":v]scale=" + size.w() + ":" + size.h() + ":force_original_aspect_ratio=decrease,pad="
                                + size.w() + ":" + size.h() + ":(ow-iw)/2:(oh-ih)/2:color=" + color + ",setsar=1,fps="
                                + fps(spec) + zoom + ",format=yuv420p[s" + i + "]");
            }
            if (pictureCount == 1) {
                parts.set(parts.size() - 1, parts.get(parts.size() - 1).replace("[s0]", "[" + current + "]"));
            } else if (spec.crossfade() && lengths.size() == pictureCount) {
                // Each picture dissolves into the next where the next one starts.
                String prev = "s0";
                long offset = 0;
                for (int i = 1; i < pictureCount; i++) {
                    offset += lengths.get(i - 1);
                    String out = i == pictureCount - 1 ? current : "x" + i;
                    parts.add(String.format(
                            Locale.ROOT,
                            "[%s][s%d]xfade=transition=fade:duration=%.3f:offset=%.3f[%s]",
                            prev,
                            i,
                            CROSSFADE_MS / 1000.0,
                            offset / 1000.0,
                            out));
                    prev = out;
                }
            } else {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < pictureCount; i++) {
                    joined.append("[s").append(i).append("]");
                }
                parts.add(joined + "concat=n=" + pictureCount + ":v=1:a=0[" + current + "]");
            }
        }

        // Title written on the picture
        if (hasTitle) {
            parts.add("[" + current + "][" + titleIn + ":v]overlay=(W-w)/2:(H-h)/2:format=auto[titled]");
            current = "titled";
        }

        // A strip of text along the top or bottom, and a logo in the top-right corner
        if (hasStrip) {
            long x = Math.round(size.w() * 0.04);
            String y = stripAtTop(spec)
                    ? String.valueOf(Math.round(size.h() * 0.05))
                    : "H-h-" + Math.round(size.h() * 0.08);
            parts.add("[" + current + "][" + stripIn + ":v]overlay=" + x + ":" + y + ":format=auto[striped]");
            current = "striped";
        }
        if (hasLogo) {
            long w = Math.max(16, Math.round(Math.min(size.w(), size.h()) * 0.18) / 2 * 2);
            long m = Math.round(Math.min(size.w(), size.h()) * 0.04);
            parts.add("[" + logoIn + ":v]scale=" + w + ":-1[logo];[" + current + "][logo]overlay=W-w-" + m + ":" + m
                    + ":format=auto[logoed]");
            current = "logoed";
        }

        // Sound: clean-up, then (optionally) split off a copy to draw the waveform from
        List<String> audio = new ArrayList<>();
        if (spec.denoise()) {
            audio.add("afftdn=nf=-25");
        }
        if (spec.normalize()) {
            // loudnorm works at a high rate internally; bring the result back to a normal one.
            audio.add("loudnorm=I=-16:TP=-1.5:LRA=11");
            audio.add("aresample=44100");
        }
        String chain = audio.isEmpty() ? "anull" : String.join(",", audio);
        if (spec.hasWaveform()) {
            parts.add("[" + audioIn + ":a]" + chain + ",asplit=2[a][aw]");
            String band = size.w() + "x" + waveHeight(size, spec.waveHeightPct());
            boolean centered = isCentered(spec.waveform());
            String wave = spec.waveColor() != null ? spec.waveColor() : contrastColor(spec.background());
            String col = "0x" + wave.substring(1).toLowerCase(Locale.ROOT);
            switch (spec.waveform()) {
                // Frequency drawings come on an opaque black field; key the black out so only the drawing is kept.
                case "BARS" ->
                    parts.add("[aw]showfreqs=s=" + band + ":mode=bar:fscale=log:ascale=sqrt:colors=" + col + ":rate="
                            + fps(spec) + ",format=rgba,colorkey=0x000000:0.1:0.2[wv]");
                case "SPECTRUM" ->
                    parts.add("[aw]showfreqs=s=" + band + ":mode=line:fscale=log:ascale=sqrt:colors=" + col + ":rate="
                            + fps(spec) + ",format=rgba,colorkey=0x000000:0.1:0.2[wv]");
                case "SPIKES" ->
                    parts.add("[aw]showwaves=s=" + band + ":mode=line:colors=" + col + ":rate=" + fps(spec)
                            + ",format=rgba[wv]");
                case "PULSE", "BLOCKS", "FINE", "STRIPES" -> {
                    // One column per bar from showwaves, widened (not smoothed) and cut into bars with gaps by an alpha
                    // mask.
                    BarLayout lay = barLayout(spec.waveform(), size, spec.waveHeightPct());
                    parts.add("[aw]showwaves=s=" + lay.bars() + "x" + lay.height() + ":mode=cline:scale=sqrt:colors="
                            + col + ":rate=" + fps(spec)
                            + ",format=rgba,scale=" + lay.width() + ":" + lay.height() + ":flags=neighbor,"
                            + "geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(mod(X," + lay.pitch() + ")," + lay.bar()
                            + "),alpha(X,Y),0)'[wv]");
                }
                case "REFLECT" -> {
                    // As the mirrored bars, with the lower half faded to look like a reflection.
                    BarLayout lay = barLayout(spec.waveform(), size, spec.waveHeightPct());
                    parts.add("[aw]showwaves=s=" + lay.bars() + "x" + lay.height() + ":mode=cline:scale=sqrt:colors="
                            + col + ":rate=" + fps(spec)
                            + ",format=rgba,scale=" + lay.width() + ":" + lay.height() + ":flags=neighbor,"
                            + "geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(mod(X," + lay.pitch() + ")," + lay.bar()
                            + "),if(gt(Y,H/2),alpha(X,Y)*0.35,alpha(X,Y)),0)'[wv]");
                }
                case "COLUMNS" -> {
                    // Mirrored bars drawn twice as tall, keeping only the top half: bars standing on the bottom edge.
                    BarLayout lay = barLayout(spec.waveform(), size, spec.waveHeightPct());
                    parts.add("[aw]showwaves=s=" + lay.bars() + "x" + lay.height() * 2
                            + ":mode=cline:scale=sqrt:colors="
                            + col + ":rate=" + fps(spec)
                            + ",format=rgba,crop=" + lay.bars() + ":" + lay.height() + ":0:0,scale=" + lay.width() + ":"
                            + lay.height() + ":flags=neighbor,"
                            + "geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(mod(X," + lay.pitch() + ")," + lay.bar()
                            + "),alpha(X,Y),0)'[wv]");
                }
                case "STEREO" ->
                    // One band per channel (a mono recording fills the band with its one channel).
                    parts.add("[aw]showwaves=s=" + band + ":mode=cline:split_channels=1:colors=" + col + "|" + col
                            + ":rate=" + fps(spec) + ",format=rgba[wv]");
                case "DOTS" ->
                    parts.add("[aw]showwaves=s=" + band + ":mode=point:colors=" + col + ":rate=" + fps(spec)
                            + ",format=rgba[wv]");
                default ->
                    parts.add("[aw]showwaves=s=" + band + ":mode=cline:colors=" + col + ":rate=" + fps(spec)
                            + ",format=rgba[wv]");
            }
            parts.add("[" + current + "][wv]overlay="
                    + (centered
                            ? "(W-w)/2:(H-h)/2"
                            : ("COLUMNS".equals(spec.waveform()) ? "(W-w)/2" : "0") + ":H-h-"
                                    + Math.round(size.h() * 0.05))
                    + ":format=auto,format=yuv420p[v]");
        } else {
            parts.add("[" + current + "]null[v]");
            parts.add("[" + audioIn + ":a]" + chain + "[a]");
        }
        return String.join(";", parts);
    }

    /**
     * The ffmpeg command: the pictures (`covers`, one per {@link #usableSlides} in order — none
     * for a plain colour) each fitted inside the frame on the background colour for their
     * stretch of time, `titlePng` written over it if given, with `audio` as the sound, cut to `durationMs`.
     */
    public static List<String> command(
            String ffmpeg,
            Spec spec,
            Path audio,
            List<Path> covers,
            Path titlePng,
            Size size,
            long durationMs,
            Path out) {
        return command(ffmpeg, spec, audio, covers, titlePng, null, null, size, durationMs, out);
    }

    /** Same, with the strip image and the logo laid over the picture when given. */
    public static List<String> command(
            String ffmpeg,
            Spec spec,
            Path audio,
            List<Path> covers,
            Path titlePng,
            Path stripPng,
            Path logo,
            Size size,
            long durationMs,
            Path out) {
        List<Slide> slides = usableSlides(spec.slides(), durationMs);
        if (covers.size() != (spec.slides().isEmpty() ? 0 : slides.size())) {
            throw new IllegalArgumentException("Expected " + slides.size() + " picture file(s), got " + covers.size());
        }
        String seconds = String.format(Locale.ROOT, "%.3f", durationMs / 1000.0);
        int fps = fps(spec);
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        if (covers.isEmpty()) {
            cmd.addAll(List.of(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=" + ffmpegColor(spec.background()) + ":s=" + size.w() + "x" + size.h() + ":r=" + fps));
        }
        List<Long> lengths = covers.isEmpty() ? List.of() : slideDurations(slides, durationMs);
        List<Long> inputs = inputLengths(spec, lengths);
        for (int i = 0; i < covers.size(); i++) {
            cmd.addAll(List.of(
                    "-loop",
                    "1",
                    "-framerate",
                    String.valueOf(fps),
                    "-t",
                    String.format(Locale.ROOT, "%.3f", inputs.get(i) / 1000.0),
                    "-i",
                    covers.get(i).toString()));
        }
        cmd.addAll(List.of("-i", audio.toString()));
        for (Path still : java.util.Arrays.asList(titlePng, stripPng, logo)) {
            if (still != null) {
                cmd.addAll(List.of("-loop", "1", "-framerate", String.valueOf(fps), "-i", still.toString()));
            }
        }
        cmd.addAll(List.of(
                "-filter_complex",
                filterGraph(spec, size, covers.size(), titlePng != null, lengths, stripPng != null, logo != null),
                "-map",
                "[v]",
                "-map",
                "[a]",
                "-c:v",
                "libx264"));
        if (!spec.hasWaveform() && !spec.smoothPictures()) {
            cmd.addAll(List.of("-tune", "stillimage"));
        }
        cmd.addAll(List.of(
                "-preset",
                "veryfast",
                "-crf",
                "26",
                "-r",
                String.valueOf(fps),
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-t",
                seconds,
                "-movflags",
                "+faststart",
                out.toString()));
        return cmd;
    }

    // ── Preparing the sound: a part of the recording, silence trimmed, chapters ──

    /** A stretch of silence found in the recording (ms). */
    public record Silence(long startMs, long endMs) {}

    /** The silences in ffmpeg silencedetect output ("silence_start: 1.2" … "silence_end: 3.4 | …"). */
    public static List<Silence> parseSilences(String log) {
        List<Silence> out = new ArrayList<>();
        Double start = null;
        for (String line : log.split("\\R")) {
            java.util.regex.Matcher s =
                    Pattern.compile("silence_start: (-?[0-9.]+)").matcher(line);
            java.util.regex.Matcher e =
                    Pattern.compile("silence_end: ([0-9.]+)").matcher(line);
            if (s.find()) {
                start = Math.max(0, Double.parseDouble(s.group(1)));
            } else if (e.find() && start != null) {
                out.add(new Silence(Math.round(start * 1000), Math.round(Double.parseDouble(e.group(1)) * 1000)));
                start = null;
            }
        }
        return out;
    }

    /** A part of the recording (ms), for chapters. */
    public record Range(long startMs, long endMs) {}

    public static final int MIN_PART_MINUTES = 2;
    public static final int MAX_PART_MINUTES = 60;

    /**
     * Where to cut a long recording into parts of about `partMs`: at the middle of the pause nearest each multiple
     * of `partMs` (within a quarter part either side), or right at it when there is none. A last bit shorter than half
     * a part joins the part before. One range covering everything when the recording isn't long enough to split.
     */
    public static List<Range> chapterRanges(List<Silence> silences, long totalMs, long partMs) {
        List<Range> out = new ArrayList<>();
        if (totalMs <= 0 || partMs <= 0) {
            return out;
        }
        long from = 0;
        while (totalMs - from > partMs * 3 / 2) {
            long target = from + partMs;
            long window = partMs / 4;
            long cut = target;
            long best = Long.MAX_VALUE;
            for (Silence s : silences) {
                long mid = (s.startMs() + s.endMs()) / 2;
                long distance = Math.abs(mid - target);
                if (distance <= window && distance < best && mid > from + partMs / 2) {
                    best = distance;
                    cut = mid;
                }
            }
            if (totalMs - cut < partMs / 2) {
                // The pause is so late that the rest would be a scrap: the rest joins this part.
                break;
            }
            out.add(new Range(from, cut));
            from = cut;
        }
        out.add(new Range(from, totalMs));
        return out;
    }

    /** Null = valid. A part of the recording must be at least a second long. */
    public static String validateRange(Long startMs, Long endMs) {
        if (startMs == null && endMs == null) {
            return null;
        }
        long a = startMs == null ? 0 : startMs;
        if (a < 0 || (endMs != null && endMs - a < 1000)) {
            return "A part of the recording must be at least 1 s long";
        }
        return null;
    }

    /** The ffmpeg audio filters that take the silence off the start and the end. */
    static String trimSilenceFilter() {
        String once = "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.3";
        return once + ",areverse," + once + ",areverse";
    }

    /** What a waveform style is called in the job log. */
    static String waveLabel(String waveform) {
        return switch (waveform) {
            case "BARS" -> "bars";
            case "SPIKES" -> "spikes";
            case "DOTS" -> "dots";
            case "SPECTRUM" -> "spectrum";
            case "PULSE" -> "pulse";
            case "BLOCKS" -> "blocks";
            case "FINE" -> "fine bars";
            case "STRIPES" -> "stripes";
            case "REFLECT" -> "reflection";
            case "COLUMNS" -> "columns";
            case "STEREO" -> "stereo waveform";
            default -> "waveform";
        };
    }

    /** A short line for the job log and the job list. */
    public static String describe(Spec spec) {
        Size size = size(spec.resolution(), spec.shape());
        StringBuilder sb = new StringBuilder("Audio to video (")
                .append(size.w())
                .append("×")
                .append(size.h());
        sb.append(
                spec.slides().size() > 1
                        ? ", " + spec.slides().size() + " pictures"
                        : !spec.slides().isEmpty()
                                ? ", cover picture"
                                : spec.drawsTitle() ? ", title card" : ", plain background");
        if (spec.hasWaveform()) {
            sb.append(", ").append(waveLabel(spec.waveform()));
        }
        if (spec.normalize()) {
            sb.append(", normalized");
        }
        if (spec.denoise()) {
            sb.append(", noise reduced");
        }
        if (spec.motion() && !spec.slides().isEmpty()) {
            sb.append(", moving pictures");
        }
        if (spec.crossfade() && spec.slides().size() > 1) {
            sb.append(", dissolves");
        }
        if (spec.hasStrip()) {
            sb.append(", title strip");
        }
        if (spec.logoKey() != null) {
            sb.append(", logo");
        }
        return sb.append(")").toString();
    }
}
