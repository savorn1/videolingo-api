package com.example.videolingo.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

// Pure bounds-checking for trim/crop/scale/split requests — no I/O, so it's
// easy to unit-test, and shared logic between VideoEditService (server-side
// enforcement) and the mirrored frontend util (inline validation before the
// API is even called).
public final class VideoEditRules {

    public static final int MAX_SEGMENTS = 20;

    /** Quarter turns clockwise, in degrees. */
    public static final List<Integer> ROTATIONS = List.of(0, 90, 180, 270);

    public record Segment(long startMs, Long endMs) {}

    private VideoEditRules() {}

    /** Null = valid; a message otherwise. `durationMs` null = unknown, so only what can be checked without it is. */
    public static String validateTrim(long startMs, Long endMs, Long durationMs) {
        if (startMs < 0) {
            return "The start can't be before the beginning";
        }
        if (endMs != null && endMs <= startMs) {
            return "The end must be after the start";
        }
        if (durationMs != null) {
            if (startMs >= durationMs) {
                return "The start is at or past the end of the video";
            }
            if (endMs != null && endMs > durationMs) {
                return "The end is past the end of the video";
            }
        }
        return null;
    }

    /** How far past the end of the video a trim may run (the last frame is held, the sound is silent there). */
    public static final long MAX_EXTEND_MS = 300_000;

    /** Like validateTrim, but the end may run up to MAX_EXTEND_MS past the end of the video. It needs an end and a known length. */
    public static String validateExtendedTrim(long startMs, Long endMs, Long durationMs) {
        if (endMs == null) {
            return "Pick an end time to extend past the end of the video";
        }
        if (durationMs == null) {
            return "The video's length isn't known, so it can't be extended";
        }
        String err = validateTrim(startMs, endMs, null);
        if (err != null) {
            return err;
        }
        if (startMs >= durationMs) {
            return "The start is at or past the end of the video";
        }
        if (endMs - durationMs > MAX_EXTEND_MS) {
            return "The end can be at most " + MAX_EXTEND_MS / 1000 + " s past the end of the video";
        }
        return null;
    }

    public static final double MAX_BLUR = 20;

    /**
     * A picture adjustment (the editor's "Filter") applied to a trim. 0 / 1 / false mean "leave it alone".
     * warmth −1…1 tints toward blue (cool) or orange (warm); jobs saved before it existed read it as 0.
     */
    public record Look(
            double brightness,
            double contrast,
            double saturation,
            double blur,
            boolean grayscale,
            boolean sepia,
            boolean vignette,
            double warmth,
            boolean sharpen) {

        /** A look without warmth or sharpening. */
        public Look(
                double brightness,
                double contrast,
                double saturation,
                double blur,
                boolean grayscale,
                boolean sepia,
                boolean vignette) {
            this(brightness, contrast, saturation, blur, grayscale, sepia, vignette, 0, false);
        }

        @JsonIgnore
        public boolean isPlain() {
            return brightness == 0
                    && contrast == 1
                    && saturation == 1
                    && blur == 0
                    && !grayscale
                    && !sepia
                    && !vignette
                    && warmth == 0
                    && !sharpen;
        }
    }

    /** Null = valid. Brightness −1…1, contrast 0…2, saturation 0…3, blur 0…MAX_BLUR, warmth −1…1. */
    public static String validateLook(Look look) {
        if (look == null) {
            return null;
        }
        if (!(look.brightness() >= -1 && look.brightness() <= 1)) {
            return "Brightness is between −100 % and +100 %";
        }
        if (!(look.contrast() >= 0 && look.contrast() <= 2)) {
            return "Contrast is between 0 % and 200 %";
        }
        if (!(look.saturation() >= 0 && look.saturation() <= 3)) {
            return "Colour is between 0 % and 300 %";
        }
        if (!(look.blur() >= 0 && look.blur() <= MAX_BLUR)) {
            return "Blur is between 0 and " + (int) MAX_BLUR;
        }
        if (!(look.warmth() >= -1 && look.warmth() <= 1)) {
            return "Warmth is between −100 % and +100 %";
        }
        return null;
    }

    /** "Brighter, more contrast, black & white"; null for a plain look. */
    public static String describeLook(Look look) {
        if (look == null || look.isPlain()) {
            return null;
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        if (look.brightness() != 0) {
            parts.add(look.brightness() > 0 ? "brighter" : "darker");
        }
        if (look.contrast() != 1) {
            parts.add(look.contrast() > 1 ? "more contrast" : "less contrast");
        }
        if (look.saturation() != 1 && !look.grayscale() && !look.sepia()) {
            parts.add(look.saturation() > 1 ? "more colour" : "less colour");
        }
        if (look.warmth() != 0 && !look.grayscale()) {
            parts.add(look.warmth() > 0 ? "warmer" : "cooler");
        }
        if (look.grayscale()) {
            parts.add("black & white");
        } else if (look.sepia()) {
            parts.add("sepia");
        }
        if (look.sharpen()) {
            parts.add("sharpened");
        }
        if (look.blur() > 0) {
            parts.add("blurred");
        }
        if (look.vignette()) {
            parts.add("vignette");
        }
        String text = String.join(", ", parts);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ── Effect and transition on a trim ──────────────────────────────────────

    /** One moving effect per trim. ZOOM_IN/OUT push in or pull back by ZOOM_AMOUNT over the whole clip. */
    public static final List<String> EFFECTS = List.of("NONE", "ZOOM_IN", "ZOOM_OUT", "GLITCH", "OLD_FILM");

    /** How far a slow zoom goes: 0.2 = the picture ends (or starts) 20 % larger. */
    public static final double ZOOM_AMOUNT = 0.2;

    /** Null = valid (null or NONE means no effect). */
    public static String validateEffect(String effect) {
        if (effect == null || EFFECTS.contains(effect)) {
            return null;
        }
        return "Pick an effect: " + String.join(", ", EFFECTS);
    }

    /** "slow zoom in"; null for none. */
    public static String describeEffect(String effect) {
        if (effect == null) {
            return null;
        }
        return switch (effect) {
            case "ZOOM_IN" -> "slow zoom in";
            case "ZOOM_OUT" -> "slow zoom out";
            case "GLITCH" -> "glitch";
            case "OLD_FILM" -> "old film";
            default -> null;
        };
    }

    public static final long MAX_FADE_MS = 5_000;
    public static final List<String> FADE_COLORS = List.of("BLACK", "WHITE");

    /** Fade the start in from, and the end out to, a colour (BLACK when left out); picture and sound together. 0 = no fade. */
    public record Fade(long inMs, long outMs, String color) {

        @JsonIgnore
        public boolean isNone() {
            return inMs == 0 && outMs == 0;
        }

        public boolean white() {
            return "WHITE".equals(color);
        }
    }

    /** Null = valid. `lengthMs` is the result's length when known; the two fades together must fit in it. */
    public static String validateFade(Fade fade, Long lengthMs) {
        if (fade == null) {
            return null;
        }
        if (fade.inMs() < 0 || fade.inMs() > MAX_FADE_MS || fade.outMs() < 0 || fade.outMs() > MAX_FADE_MS) {
            return "A fade is between 0 and " + MAX_FADE_MS / 1000 + " s";
        }
        if (fade.color() != null && !FADE_COLORS.contains(fade.color())) {
            return "Pick a fade colour: " + String.join(", ", FADE_COLORS);
        }
        if (lengthMs != null && fade.inMs() + fade.outMs() > lengthMs) {
            return "The fade in and fade out together can't be longer than the clip";
        }
        return null;
    }

    /** "fade in 1 s, fade out 0.5 s (white)"; null for none. */
    public static String describeFade(Fade fade) {
        if (fade == null || fade.isNone()) {
            return null;
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        if (fade.inMs() > 0) {
            parts.add("fade in " + seconds(fade.inMs()));
        }
        if (fade.outMs() > 0) {
            parts.add("fade out " + seconds(fade.outMs()));
        }
        return String.join(", ", parts) + (fade.white() ? " (white)" : "");
    }

    private static String seconds(long ms) {
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0)) + " s";
    }

    // ── Freeze frame, GIF and still image ────────────────────────────────────

    public static final long MIN_FREEZE_MS = 500;
    public static final long MAX_FREEZE_MS = 10_000;

    /** Hold the frame at atMs (on the trimmed result's timeline) for durationMs; the sound pauses there. */
    public record Freeze(long atMs, long durationMs) {}

    /** Null = valid (or none). `clipMs` is the trimmed result's length when known. */
    public static String validateFreeze(Freeze freeze, Long clipMs) {
        if (freeze == null) {
            return null;
        }
        if (freeze.durationMs() < MIN_FREEZE_MS || freeze.durationMs() > MAX_FREEZE_MS) {
            return "A freeze lasts between " + MIN_FREEZE_MS / 1000.0 + " and " + MAX_FREEZE_MS / 1000 + " s";
        }
        if (freeze.atMs() <= 0) {
            return "Freeze a frame after the start of the clip";
        }
        if (clipMs != null && freeze.atMs() >= clipMs) {
            return "Freeze a frame before the end of the clip";
        }
        return null;
    }

    /** "frame held 2 s"; null for none. */
    public static String describeFreeze(Freeze freeze) {
        return freeze == null ? null : "frame held " + seconds(freeze.durationMs());
    }

    /** A GIF is cut from a short range, at one of these widths (height follows), 12 frames a second. */
    public static final long MAX_GIF_MS = 15_000;

    public static final List<Integer> GIF_WIDTHS = List.of(320, 480, 640);
    public static final int GIF_FPS = 12;

    public static String validateGif(long startMs, long endMs, Integer width, Long videoMs) {
        String err = validateTrim(startMs, endMs, videoMs);
        if (err != null) {
            return err;
        }
        if (endMs - startMs > MAX_GIF_MS) {
            return "A GIF can be at most " + MAX_GIF_MS / 1000 + " s long";
        }
        if (width != null && !GIF_WIDTHS.contains(width)) {
            return "Pick a GIF width: 320, 480 or 640";
        }
        return null;
    }

    public static String validateStill(long atMs, Long videoMs) {
        if (atMs < 0) {
            return "The picture can't be before the beginning";
        }
        if (videoMs != null && atMs >= videoMs) {
            return "The picture is past the end of the video";
        }
        return null;
    }

    // ── Blur boxes, speed ramp, picture-in-picture, intro/outro cards ─────────

    public static final int MAX_BLURS = 5;
    public static final int MIN_BLUR_SIZE = 8;

    /** A box blurred for the whole clip, in the original picture's pixels (like the crop, which comes after). */
    public record BlurBox(int x, int y, int w, int h) {}

    public static String validateBlurs(List<BlurBox> boxes, Integer videoWidth, Integer videoHeight) {
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        if (boxes.size() > MAX_BLURS) {
            return "At most " + MAX_BLURS + " blurred areas";
        }
        for (int i = 0; i < boxes.size(); i++) {
            BlurBox b = boxes.get(i);
            if (b.w() < MIN_BLUR_SIZE || b.h() < MIN_BLUR_SIZE) {
                return "Blurred area " + (i + 1) + " is too small";
            }
            String err = validateCrop(b.x(), b.y(), b.w(), b.h(), videoWidth, videoHeight);
            if (err != null) {
                return "Blurred area " + (i + 1) + ": "
                        + err.replace("The crop", "it").replace("Crop", "its");
            }
        }
        return null;
    }

    /** Part of the clip played slower or faster (times on the trimmed clip's timeline); the sound keeps its pitch. */
    public record SpeedRange(long startMs, long endMs, double speed) {}

    public static final List<Double> RAMP_SPEEDS = List.of(0.25, 0.5, 0.75, 1.5, 2.0, 4.0);
    public static final long MIN_RAMP_MS = 500;

    public static String validateSpeedRange(SpeedRange r, Long clipMs) {
        if (r == null) {
            return null;
        }
        if (!RAMP_SPEEDS.contains(r.speed())) {
            return "Pick a speed: 0.25×, 0.5×, 0.75×, 1.5×, 2× or 4×";
        }
        if (r.startMs() < 0 || r.endMs() - r.startMs() < MIN_RAMP_MS) {
            return "The part to speed up or slow down must be at least " + MIN_RAMP_MS / 1000.0 + " s long";
        }
        if (clipMs != null && r.endMs() > clipMs) {
            return "The part to speed up or slow down runs past the end of the clip";
        }
        return null;
    }

    /** Where a moment of the trimmed clip ends up once the range plays at its speed. */
    public static long rampedMs(long ms, SpeedRange r) {
        if (r == null || ms <= r.startMs()) {
            return ms;
        }
        long inside = Math.min(ms, r.endMs()) - r.startMs();
        long after = Math.max(0, ms - r.endMs());
        return r.startMs() + Math.round(inside / r.speed()) + after;
    }

    public static String describeSpeedRange(SpeedRange r) {
        if (r == null) {
            return null;
        }
        String speed = r.speed() == Math.rint(r.speed()) ? String.valueOf((long) r.speed()) : String.valueOf(r.speed());
        return (r.speed() < 1 ? "slow motion " : "sped up ") + speed + "× for " + seconds(r.endMs() - r.startMs());
    }

    /** Another stored video in a corner, `sizePct` of the width, muted; it starts with the clip and disappears when it ends. */
    public record Pip(Long videoId, String corner, double sizePct) {}

    public static final List<String> PIP_CORNERS = List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT");
    public static final double MIN_PIP_PCT = 15;
    public static final double MAX_PIP_PCT = 50;

    /** Null = valid; whether the video exists and is a stored file is checked by the service. */
    public static String validatePip(Pip p) {
        if (p == null) {
            return null;
        }
        if (p.videoId() == null) {
            return "Picture in picture: pick a video";
        }
        if (p.corner() != null && !PIP_CORNERS.contains(p.corner())) {
            return "Picture in picture: pick a corner";
        }
        if (p.sizePct() < MIN_PIP_PCT || p.sizePct() > MAX_PIP_PCT) {
            return "Picture in picture: the size must be between " + (int) MIN_PIP_PCT + "% and " + (int) MAX_PIP_PCT
                    + "%";
        }
        return null;
    }

    /** A title card: `text` centred on the background for `durationMs`, fading in and out, silent. */
    public record Card(String text, long durationMs) {}

    /** Cards before and/or after the clip; colours are #RRGGBB; logoKey = an uploaded image (overlay upload) above the text. */
    public record Cards(Card intro, Card outro, String background, String color, String logoKey) {}

    public static final long MIN_CARD_MS = 1000;
    public static final long MAX_CARD_MS = 6000;
    public static final int MAX_CARD_TEXT = 120;

    public static String validateCards(Cards c) {
        if (c == null) {
            return null;
        }
        if (c.intro() == null && c.outro() == null) {
            return "Add an intro or an outro card";
        }
        for (Card card : java.util.Arrays.asList(c.intro(), c.outro())) {
            if (card == null) {
                continue;
            }
            String which = card == c.intro() ? "Intro" : "Outro";
            if (card.text() == null || card.text().isBlank()) {
                return which + " card: write its text";
            }
            if (card.text().length() > MAX_CARD_TEXT) {
                return which + " card: the text can be at most " + MAX_CARD_TEXT + " characters";
            }
            if (card.durationMs() < MIN_CARD_MS || card.durationMs() > MAX_CARD_MS) {
                return which + " card: it lasts between " + MIN_CARD_MS / 1000 + " and " + MAX_CARD_MS / 1000 + " s";
            }
        }
        if (!isHexColour(c.background()) || !isHexColour(c.color())) {
            return "Cards: pick the colours";
        }
        if (c.logoKey() != null && !c.logoKey().startsWith(OverlayRules.UPLOAD_PREFIX)) {
            return "Cards: the logo isn't an uploaded image";
        }
        return null;
    }

    public static String describeCards(Cards c) {
        if (c == null) {
            return null;
        }
        return c.intro() != null && c.outro() != null
                ? "intro and outro cards"
                : c.intro() != null ? "intro card" : "outro card";
    }

    static boolean isHexColour(String s) {
        return s != null && s.matches("#[0-9a-fA-F]{6}");
    }

    /** Only called once a crop is actually requested — x/y/w/h are all given. */
    public static String validateCrop(int x, int y, int w, int h, Integer videoWidth, Integer videoHeight) {
        if (w <= 0 || h <= 0) {
            return "Crop width and height must be positive";
        }
        if (x < 0 || y < 0) {
            return "Crop position can't be negative";
        }
        if (videoWidth != null && x + w > videoWidth) {
            return "The crop extends past the right edge of the video";
        }
        if (videoHeight != null && y + h > videoHeight) {
            return "The crop extends past the bottom edge of the video";
        }
        return null;
    }

    /** Only called once a resize is actually requested — w/h are both given. */
    public static String validateScale(int w, int h) {
        return w <= 0 || h <= 0 ? "The resized width and height must be positive" : null;
    }

    /** Only called once a rotation is requested. Null = valid. */
    public static String validateRotation(Integer degrees) {
        return degrees == null || ROTATIONS.contains(degrees)
                ? null
                : "The picture can be turned 90, 180 or 270 degrees";
    }

    /** Whether a rotation or flip was asked for at all (a null or 0 turn with no flips is "leave it alone"). */
    public static boolean hasOrientation(Integer rotate, Boolean flipH, Boolean flipV) {
        return (rotate != null && rotate != 0) || Boolean.TRUE.equals(flipH) || Boolean.TRUE.equals(flipV);
    }

    /** True when the turn swaps the picture's width and height (90 or 270). */
    public static boolean swapsSides(Integer rotate) {
        return rotate != null && (rotate == 90 || rotate == 270);
    }

    /** "Turned 90° clockwise, flipped horizontally"; null when nothing is asked for. */
    public static String describeOrientation(Integer rotate, Boolean flipH, Boolean flipV) {
        if (!hasOrientation(rotate, flipH, flipV)) {
            return null;
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        if (rotate != null && rotate != 0) {
            parts.add("Turned " + rotate + "° clockwise");
        }
        if (Boolean.TRUE.equals(flipH)) {
            parts.add("flipped left–right");
        }
        if (Boolean.TRUE.equals(flipV)) {
            parts.add("flipped top–bottom");
        }
        String text = String.join(", ", parts);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The shortest piece of the video that may be left between (or around) cuts. */
    public static final long MIN_KEPT_MS = 500;

    /** Cuts sorted by start, with overlapping or touching ones joined. A null end means "to the end". */
    public static List<Segment> mergeCuts(List<Segment> cuts) {
        List<Segment> sorted = cuts.stream()
                .sorted(java.util.Comparator.comparingLong(Segment::startMs))
                .toList();
        java.util.ArrayList<Segment> merged = new java.util.ArrayList<>();
        for (Segment c : sorted) {
            Segment last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && (last.endMs() == null || c.startMs() <= last.endMs())) {
                Long end = last.endMs() == null || c.endMs() == null ? null : Math.max(last.endMs(), c.endMs());
                merged.set(merged.size() - 1, new Segment(last.startMs(), end));
            } else {
                merged.add(c);
            }
        }
        return merged;
    }

    /** What stays once the (already merged) cuts are taken out; a null end means "to the end" of a video of unknown length. */
    public static List<Segment> keptRanges(List<Segment> mergedCuts, Long durationMs) {
        java.util.ArrayList<Segment> kept = new java.util.ArrayList<>();
        long pos = 0;
        for (Segment c : mergedCuts) {
            if (c.startMs() > pos) {
                kept.add(new Segment(pos, c.startMs()));
            }
            if (c.endMs() == null) {
                return kept;
            }
            pos = Math.max(pos, c.endMs());
        }
        if (durationMs == null) {
            kept.add(new Segment(pos, null));
        } else if (pos < durationMs) {
            kept.add(new Segment(pos, durationMs));
        }
        return kept;
    }

    /** Null = valid. Ranges to cut out of the video: each a valid range, and what's left must not be empty or shorter than MIN_KEPT_MS in any piece. */
    public static String validateCuts(List<Segment> cuts, Long durationMs) {
        if (cuts == null || cuts.isEmpty()) {
            return "Mark at least one range to cut out";
        }
        if (cuts.size() > MAX_SEGMENTS) {
            return "At most " + MAX_SEGMENTS + " cuts at a time";
        }
        for (int i = 0; i < cuts.size(); i++) {
            Segment c = cuts.get(i);
            String err = validateTrim(c.startMs(), c.endMs(), durationMs);
            if (err != null) {
                return "Cut " + (i + 1) + ": " + err;
            }
        }
        List<Segment> kept = keptRanges(mergeCuts(cuts), durationMs);
        if (kept.isEmpty()) {
            return "Nothing would be left of the video";
        }
        for (Segment k : kept) {
            if (k.endMs() != null && k.endMs() - k.startMs() < MIN_KEPT_MS) {
                return "A piece left between the cuts would be shorter than " + MIN_KEPT_MS + " ms";
            }
        }
        return null;
    }

    public static String validateSegments(List<Segment> segments, Long durationMs) {
        if (segments == null || segments.isEmpty()) {
            return "Add at least one segment";
        }
        if (segments.size() > MAX_SEGMENTS) {
            return "At most " + MAX_SEGMENTS + " segments at a time";
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            String err = validateTrim(s.startMs(), s.endMs(), durationMs);
            if (err != null) {
                return "Segment " + (i + 1) + ": " + err;
            }
        }
        return null;
    }
}
