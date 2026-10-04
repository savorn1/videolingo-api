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
            List<Slide> slides) {

        public Spec {
            slides = slides == null ? List.of() : List.copyOf(slides);
            if (slides.isEmpty() && coverKey != null) {
                slides = List.of(new Slide(coverKey, 0));
            } else if (coverKey == null && !slides.isEmpty()) {
                coverKey = slides.get(0).key();
            }
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

        public boolean hasWaveform() {
            return waveform != null && !waveform.equals("NONE");
        }

        /** The title is only drawn when there is no picture to show instead. */
        public boolean drawsTitle() {
            return titleCard && slides.isEmpty() && titleText != null && !titleText.isBlank();
        }
    }

    public record Size(int w, int h) {}

    /** Output sizes on offer, all 16:9 with even sides. */
    public static final List<String> RESOLUTIONS = List.of("360p", "480p", "720p", "1080p");

    public static final String DEFAULT_RESOLUTION = "720p";
    public static final String DEFAULT_BACKGROUND = "#111827";
    /**
     * WAVES = a centred line, SPIKES = vertical sticks, DOTS = a trail of points, BARS = frequency bars, SPECTRUM = a thin frequency line,
     * PULSE = thin evenly spaced bars, BLOCKS = thick ones, FINE = hair-thin dense ones and STRIPES = medium ones, all drawn mirrored around the middle of the picture.
     */
    public static final List<String> WAVEFORMS =
            List.of("NONE", "WAVES", "BARS", "SPIKES", "DOTS", "SPECTRUM", "PULSE", "BLOCKS", "FINE", "STRIPES");

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

    static int fps(Spec spec) {
        return spec.hasWaveform() ? WAVE_FPS : STILL_FPS;
    }

    /** The styles of separate mirrored bars, drawn in the middle of the picture rather than along the bottom. */
    public static final List<String> BAR_STYLES = List.of("PULSE", "BLOCKS", "FINE", "STRIPES");

    public static boolean isCentered(String waveform) {
        return BAR_STYLES.contains(waveform);
    }

    /**
     * How the centred bar styles are laid out: `bars` columns, each `pitch` pixels apart and `bar` pixels wide, across `width`
     * pixels, `height` tall. Whole pixels, so every bar is as crisp as the next.
     */
    public record BarLayout(int bars, int pitch, int bar, int width, int height) {}

    public static BarLayout barLayout(String waveform, Size size) {
        // How many bars span the picture, and how much of each bar's slot is filled.
        double count =
                switch (waveform) {
                    case "BLOCKS" -> 36.0;
                    case "STRIPES" -> 60.0;
                    case "FINE" -> 150.0;
                    default -> 90.0;
                };
        double fill =
                switch (waveform) {
                    case "BLOCKS" -> 0.6;
                    case "STRIPES" -> 0.5;
                    case "FINE" -> 0.35;
                    default -> 0.4;
                };
        int target = (int) Math.round(size.w() * 0.8);
        int pitch = Math.max("BLOCKS".equals(waveform) ? 12 : 4, (int) Math.round(target / count));
        int bars = Math.max(8, target / pitch);
        int bar = Math.max(2, (int) Math.round(pitch * fill));
        int height = (int) Math.round(size.h() * 0.4) / 2 * 2;
        return new BarLayout(bars, pitch, bar, bars * pitch / 2 * 2, height);
    }

    /** Height of the waveform band: a quarter of the frame, kept even. */
    static int waveHeight(Size size) {
        return (size.h() / 4 / 2) * 2;
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
        String color = ffmpegColor(spec.background());
        List<String> parts = new ArrayList<>();

        // Picture: one input per slide (or a colour), each fitted onto the background, then run one after another
        int pictures = Math.max(1, pictureCount);
        int audioIn = pictures;
        int titleIn = pictures + 1;
        String current = "pic";
        if (pictureCount == 0) {
            parts.add("[0:v]setsar=1,format=yuv420p[" + current + "]");
        } else {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < pictureCount; i++) {
                parts.add(
                        "[" + i + ":v]scale=" + size.w() + ":" + size.h() + ":force_original_aspect_ratio=decrease,pad="
                                + size.w() + ":" + size.h() + ":(ow-iw)/2:(oh-ih)/2:color=" + color + ",setsar=1,fps="
                                + fps(spec) + ",format=yuv420p[s" + i + "]");
                joined.append("[s").append(i).append("]");
            }
            if (pictureCount == 1) {
                parts.set(parts.size() - 1, parts.get(parts.size() - 1).replace("[s0]", "[" + current + "]"));
            } else {
                parts.add(joined + "concat=n=" + pictureCount + ":v=1:a=0[" + current + "]");
            }
        }

        // Title written on the picture
        if (hasTitle) {
            parts.add("[" + current + "][" + titleIn + ":v]overlay=(W-w)/2:(H-h)/2:format=auto[titled]");
            current = "titled";
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
            String band = size.w() + "x" + waveHeight(size);
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
                    BarLayout lay = barLayout(spec.waveform(), size);
                    parts.add("[aw]showwaves=s=" + lay.bars() + "x" + lay.height() + ":mode=cline:scale=sqrt:colors="
                            + col + ":rate=" + fps(spec)
                            + ",format=rgba,scale=" + lay.width() + ":" + lay.height() + ":flags=neighbor,"
                            + "geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(mod(X," + lay.pitch() + ")," + lay.bar()
                            + "),alpha(X,Y),0)'[wv]");
                }
                case "DOTS" ->
                    parts.add("[aw]showwaves=s=" + band + ":mode=point:colors=" + col + ":rate=" + fps(spec)
                            + ",format=rgba[wv]");
                default ->
                    parts.add("[aw]showwaves=s=" + band + ":mode=cline:colors=" + col + ":rate=" + fps(spec)
                            + ",format=rgba[wv]");
            }
            parts.add("[" + current + "][wv]overlay="
                    + (centered ? "(W-w)/2:(H-h)/2" : "0:H-h-" + Math.round(size.h() * 0.05))
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
        } else {
            List<Long> lengths = slideDurations(slides, durationMs);
            for (int i = 0; i < covers.size(); i++) {
                cmd.addAll(List.of(
                        "-loop",
                        "1",
                        "-framerate",
                        String.valueOf(fps),
                        "-t",
                        String.format(Locale.ROOT, "%.3f", lengths.get(i) / 1000.0),
                        "-i",
                        covers.get(i).toString()));
            }
        }
        cmd.addAll(List.of("-i", audio.toString()));
        if (titlePng != null) {
            cmd.addAll(List.of("-loop", "1", "-framerate", String.valueOf(fps), "-i", titlePng.toString()));
        }
        cmd.addAll(List.of(
                "-filter_complex",
                filterGraph(spec, size, covers.size(), titlePng != null),
                "-map",
                "[v]",
                "-map",
                "[a]",
                "-c:v",
                "libx264"));
        if (!spec.hasWaveform()) {
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
            default -> "waveform";
        };
    }

    /** A short line for the job log and the job list. */
    public static String describe(Spec spec) {
        Size size = size(spec.resolution());
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
        return sb.append(")").toString();
    }
}
