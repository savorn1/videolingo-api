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

    /** At most this many held frames in one trim (e.g. a pause after every sentence). */
    public static final int MAX_HOLDS = 60;

    /**
     * The freeze and the pauses as one list for the render: in time order, leaving out any within 0.5 s of the one
     * kept before it (a freeze placed on a sentence's end), which would leave an empty piece between them.
     */
    public static java.util.List<long[]> spacedHolds(java.util.List<long[]> holds) {
        java.util.List<long[]> sorted = holds.stream()
                .sorted(java.util.Comparator.comparingLong(h -> h[0]))
                .toList();
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (long[] h : sorted) {
            if (out.isEmpty() || h[0] - out.get(out.size() - 1)[0] >= MIN_FREEZE_MS) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * Several held frames ("pause after each sentence"): each like a freeze, in time order on the trimmed clip, at
     * least half a second apart. Null = valid (or none).
     */
    public static String validateHolds(java.util.List<Freeze> holds, Long clipMs) {
        if (holds == null || holds.isEmpty()) {
            return null;
        }
        if (holds.size() > MAX_HOLDS) {
            return "At most " + MAX_HOLDS + " pauses in one trim";
        }
        long last = 0;
        for (int i = 0; i < holds.size(); i++) {
            Freeze h = holds.get(i);
            String err = validateFreeze(h, clipMs);
            if (err != null) {
                return "Pause " + (i + 1) + ": " + Character.toLowerCase(err.charAt(0)) + err.substring(1);
            }
            if (i > 0 && h.atMs() - last < 500) {
                return "Pause " + (i + 1) + ": pauses must be at least half a second apart, in order";
            }
            last = h.atMs();
        }
        return null;
    }

    /** "5 pauses of 2 s"; null for none. */
    public static String describeHolds(java.util.List<Freeze> holds) {
        if (holds == null || holds.isEmpty()) {
            return null;
        }
        return holds.size() + (holds.size() == 1 ? " pause" : " pauses") + " of "
                + seconds(holds.get(0).durationMs());
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
        return rampedAcross(ms, r == null ? List.of() : List.of(r));
    }

    /** At most this many parts at another speed in one trim. */
    public static final int MAX_SPEED_PARTS = 10;

    /** The single part (`speed`, older requests) and the list (`speeds`) as one list in time order. */
    public static List<SpeedRange> speedParts(SpeedRange one, List<SpeedRange> more) {
        List<SpeedRange> all = new java.util.ArrayList<>();
        if (one != null) {
            all.add(one);
        }
        if (more != null) {
            all.addAll(more);
        }
        all.sort(java.util.Comparator.comparingLong(SpeedRange::startMs));
        return all;
    }

    /** Each part valid on its own, at most MAX_SPEED_PARTS, and none overlapping another. */
    public static String validateSpeedParts(SpeedRange one, List<SpeedRange> more, Long clipMs) {
        List<SpeedRange> all = speedParts(one, more);
        if (all.size() > MAX_SPEED_PARTS) {
            return "At most " + MAX_SPEED_PARTS + " parts at another speed";
        }
        for (int i = 0; i < all.size(); i++) {
            String err = validateSpeedRange(all.get(i), clipMs);
            if (err != null) {
                return all.size() == 1 ? err : "Speed part " + (i + 1) + ": " + err;
            }
            if (i > 0 && all.get(i).startMs() < all.get(i - 1).endMs()) {
                return "The parts at another speed overlap — move one so they don't";
            }
        }
        return null;
    }

    /** Where a moment of the trimmed clip ends up once each part (in time order, apart) plays at its speed. */
    public static long rampedAcross(long ms, List<SpeedRange> parts) {
        double shift = 0;
        for (SpeedRange r : parts) {
            if (ms <= r.startMs()) {
                break;
            }
            long inside = Math.min(ms, r.endMs()) - r.startMs();
            shift += inside / r.speed() - inside;
        }
        return ms + Math.round(shift);
    }

    public static String describeSpeedParts(List<SpeedRange> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return parts.size() == 1 ? describeSpeedRange(parts.get(0)) : parts.size() + " parts at another speed";
    }

    public static String describeSpeedRange(SpeedRange r) {
        if (r == null) {
            return null;
        }
        String speed = r.speed() == Math.rint(r.speed()) ? String.valueOf((long) r.speed()) : String.valueOf(r.speed());
        return (r.speed() < 1 ? "slow motion " : "sped up ") + speed + "× for " + seconds(r.endMs() - r.startMs());
    }

    // ── Reverse, stabilise, zoom to an area, green screen ────────────────────

    /** Reversing holds the whole clip in memory, so only short clips can be played backwards. */
    public static final long MAX_REVERSE_MS = 60_000;

    public static String validateReverse(Boolean reverse, Long clipMs) {
        if (Boolean.TRUE.equals(reverse) && clipMs != null && clipMs > MAX_REVERSE_MS) {
            return "Only a clip up to " + MAX_REVERSE_MS / 1000 + " s can be played backwards — trim it shorter";
        }
        return null;
    }

    /**
     * Zoom from the whole picture into an area over the clip: the area's centre (cx, cy, 0–1 of the trimmed
     * picture) and its final size (a fraction of the picture, its shape kept).
     */
    public record ZoomTo(double cx, double cy, double size) {}

    public static final double MIN_ZOOM_SIZE = 0.25;
    public static final double MAX_ZOOM_SIZE = 0.95;

    public static String validateZoomTo(ZoomTo z) {
        if (z == null) {
            return null;
        }
        if (z.cx() < 0 || z.cx() > 1 || z.cy() < 0 || z.cy() > 1) {
            return "Zoom: the area must be inside the picture";
        }
        if (z.size() < MIN_ZOOM_SIZE || z.size() > MAX_ZOOM_SIZE) {
            return "Zoom: the area is between " + Math.round(MIN_ZOOM_SIZE * 100) + "% and "
                    + Math.round(MAX_ZOOM_SIZE * 100) + "% of the picture";
        }
        return null;
    }

    public static String describeZoomTo(ZoomTo z) {
        return z == null ? null : "zoom into " + Math.round(z.size() * 100) + "% of the picture";
    }

    /** Green screen: `keyColor` (#RRGGBB, green when left out) becomes `background` (#RRGGBB). */
    public record GreenScreen(String keyColor, double similarity, String background) {
        public String key() {
            return keyColor == null ? "#00FF00" : keyColor;
        }
    }

    public static final double MIN_KEY_SIMILARITY = 0.05;
    public static final double MAX_KEY_SIMILARITY = 0.6;

    public static String validateGreenScreen(GreenScreen g) {
        if (g == null) {
            return null;
        }
        if (!isHexColour(g.key()) || !isHexColour(g.background())) {
            return "Green screen: pick the colours";
        }
        if (g.similarity() < MIN_KEY_SIMILARITY || g.similarity() > MAX_KEY_SIMILARITY) {
            return "Green screen: the tolerance is between " + Math.round(MIN_KEY_SIMILARITY * 100) + " and "
                    + Math.round(MAX_KEY_SIMILARITY * 100);
        }
        return null;
    }

    /** "played backwards, stabilised, green screen" — whichever apply; null for none. */
    public static String describeMotion(Boolean reverse, Boolean stabilize, GreenScreen green) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(reverse)) {
            parts.add("played backwards");
        }
        if (Boolean.TRUE.equals(stabilize)) {
            parts.add("stabilised");
        }
        if (green != null) {
            parts.add("green screen");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
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

    /** The longest name a split part may have (it ends its new video's title). */
    public static final int MAX_PART_LABEL = 80;

    /** What is wrong with a split part's name and shape (both optional), or null. */
    public static String validateSplitPart(String label, Double aspect, Integer width, Integer height) {
        if (label != null && label.strip().length() > MAX_PART_LABEL) {
            return "The name is longer than " + MAX_PART_LABEL + " characters";
        }
        if (aspect == null) {
            return null;
        }
        if (!(aspect >= ReframeRules.MIN_ASPECT && aspect <= ReframeRules.MAX_ASPECT)) {
            return "The shape must be between 1:4 and 4:1";
        }
        if (width == null || height == null || width <= 0 || height <= 0) {
            return "The video's size isn't known yet, so it can't be cropped to a shape";
        }
        return null;
    }

    /**
     * A split part's crop for its shape: the largest box of `aspect` (w/h) in the frame, centred, with even
     * sides (the encoder needs them). Null when there's no shape, the size isn't known, or it is the frame's own shape.
     */
    public static MediaTools.CropRect splitCrop(Double aspect, Integer width, Integer height) {
        if (aspect == null || width == null || height == null || width <= 0 || height <= 0) {
            return null;
        }
        AutoCropRules.CropRect box = AutoCropRules.suggestCrop(aspect, width, height, 0.5, 0.5);
        int w = Math.max(2, box.w() / 2 * 2);
        int h = Math.max(2, box.h() / 2 * 2);
        if (w >= width - 1 && h >= height - 1) {
            return null;
        }
        return new MediaTools.CropRect((width - w) / 2, (height - h) / 2, w, h);
    }

    // ── Punch-in zooms ───────────────────────────────────────────────────────
    /** A quick zoom in to `scale` (e.g. 1.12) for each moment (times on the trimmed clip), back to the whole picture between. */
    public record PunchIn(double scale, List<Segment> ranges) {}

    public static final double MIN_PUNCH = 1.05;
    public static final double MAX_PUNCH = 1.5;
    public static final int MAX_PUNCH_RANGES = 60;
    public static final long MIN_PUNCH_MS = 300;

    public static String validatePunchIn(PunchIn p, Long clipMs) {
        if (p == null) {
            return null;
        }
        if (!(p.scale() >= MIN_PUNCH && p.scale() <= MAX_PUNCH)) {
            return "Punch-in: the zoom is between 105% and 150%";
        }
        if (p.ranges() == null || p.ranges().isEmpty()) {
            return "Punch-in: there are no moments to zoom in on";
        }
        if (p.ranges().size() > MAX_PUNCH_RANGES) {
            return "Punch-in: at most " + MAX_PUNCH_RANGES + " moments";
        }
        long previousEnd = 0;
        for (int i = 0; i < p.ranges().size(); i++) {
            Segment r = p.ranges().get(i);
            if (r.endMs() == null || r.startMs() < 0 || r.endMs() - r.startMs() < MIN_PUNCH_MS) {
                return "Punch-in " + (i + 1) + ": a moment is at least " + MIN_PUNCH_MS / 1000.0 + " s long";
            }
            if (clipMs != null && r.endMs() > clipMs) {
                return "Punch-in " + (i + 1) + ": it runs past the end of the clip";
            }
            if (r.startMs() < previousEnd) {
                return "Punch-in moments must be in time order and not overlap";
            }
            previousEnd = r.endMs();
        }
        return null;
    }

    /** The zoom over time, an expression of t (seconds): `scale` inside the moments, 1 between them. */
    public static String punchFactor(PunchIn p) {
        String inside = p.ranges().stream()
                .map(r -> String.format(
                        java.util.Locale.ROOT, "between(t,%.3f,%.3f)", r.startMs() / 1000.0, r.endMs() / 1000.0))
                .collect(java.util.stream.Collectors.joining("+"));
        return String.format(java.util.Locale.ROOT, "(1+%.4f*min(1,%s))", p.scale() - 1, inside);
    }

    // ── Progress bar ─────────────────────────────────────────────────────────
    private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    public static String validateProgressBar(String color) {
        return color == null || HEX_COLOR.matcher(color).matches() ? null : "Progress bar: pick a colour";
    }

    /** The bar's height: about 1.2% of the picture, at least 4 px, even. */
    static int progressBarHeight(int height) {
        return Math.max(4, (int) Math.round(height * 0.012)) / 2 * 2;
    }

    /**
     * A faint track along the bottom edge and a bar of `color` sliding in over it as the clip plays (the same
     * drawing as a video made from audio); the sound passes through.
     */
    public static String progressBarGraph(int width, int height, String color, long clipMs, boolean hasAudio) {
        int h = progressBarHeight(height);
        double seconds = Math.max(0.001, clipMs / 1000.0);
        String col = "0x" + color.substring(1).toLowerCase(java.util.Locale.ROOT);
        return String.format(
                        java.util.Locale.ROOT,
                        "color=c=%s:s=%dx%d:d=%.3f[bar];[0:v]drawbox=x=0:y=ih-%d:w=iw:h=%d:color=%s@0.25:t=fill[track];"
                                + "[track][bar]overlay=x='-w+w*t/%.3f':y=H-h:eval=frame[v]",
                        col,
                        width,
                        h,
                        seconds,
                        h,
                        h,
                        col,
                        seconds)
                + (hasAudio ? ";[0:a]anull[a]" : "");
    }

    // ── Colour grade (.cube LUT) ─────────────────────────────────────────────
    public static final String LUT_PREFIX = "luts/";
    private static final java.util.regex.Pattern LUT_KEY =
            java.util.regex.Pattern.compile("^luts/[0-9a-f-]{36}\\.cube$");

    public static String validateLutKey(String key) {
        return key == null || LUT_KEY.matcher(key).matches() ? null : "The colour grade isn't an upload this server made";
    }

    // ── Test render ──────────────────────────────────────────────────────────
    /** A test render is the clip's first SAMPLE_MS, with its short side at most SAMPLE_SIDE. */
    public static final long SAMPLE_MS = 8000;

    public static final int SAMPLE_SIDE = 360;
    public static final String SAMPLE_SUMMARY = "Test render: the first " + SAMPLE_MS / 1000 + " s, small";

    /**
     * A test render of a trim: the same settings on the clip's first SAMPLE_MS (timed settings past it left out
     * or cut short) at a small size, to check what the preview can't show before rendering it all.
     */
    public static EditJobParams.Trim sample(EditJobParams.Trim t, Integer videoW, Integer videoH, Long videoMs) {
        long start = t.startMs();
        Long fullEnd = t.endMs() != null ? t.endMs() : videoMs;
        long end = fullEnd == null ? start + SAMPLE_MS : Math.min(fullEnd, start + SAMPLE_MS);
        long clipMs = end - start;
        long pad = videoMs != null ? Math.max(0, end - videoMs) : 0;
        Fade fade = t.fade();
        if (fade != null && fade.inMs() + fade.outMs() > clipMs) {
            fade = new Fade(Math.min(fade.inMs(), clipMs), 0, fade.color());
        }
        Freeze freeze = t.freeze() != null && t.freeze().atMs() < clipMs ? t.freeze() : null;
        List<Freeze> holds = t.holds() == null
                ? List.of()
                : t.holds().stream().filter(h -> h.atMs() < clipMs).toList();
        List<SpeedRange> speeds = speedParts(t.speed(), t.speeds()).stream()
                .filter(r -> r.startMs() < clipMs)
                .map(r -> new SpeedRange(r.startMs(), Math.min(r.endMs(), clipMs), r.speed()))
                .filter(r -> r.endMs() - r.startMs() >= MIN_RAMP_MS)
                .toList();
        PunchIn punch = null;
        if (t.punchIn() != null) {
            List<Segment> ranges = t.punchIn().ranges().stream()
                    .filter(r -> r.startMs() < clipMs)
                    .map(r -> new Segment(r.startMs(), Math.min(r.endMs(), clipMs)))
                    .filter(r -> r.endMs() - r.startMs() >= MIN_PUNCH_MS)
                    .toList();
            punch = ranges.isEmpty() ? null : new PunchIn(t.punchIn().scale(), ranges);
        }
        return new EditJobParams.Trim(
                "TRIM",
                start,
                end,
                t.crop(),
                sampleScale(t, videoW, videoH),
                t.rotate(),
                t.flipH(),
                t.flipV(),
                t.audio(),
                pad > 0 ? pad : null,
                t.look(),
                t.effect(),
                fade,
                freeze,
                t.blurs(),
                null,
                t.pip(),
                t.cards(),
                t.reverse(),
                t.stabilize(),
                t.zoomTo(),
                t.greenScreen(),
                t.reframe(),
                holds.isEmpty() ? null : holds,
                speeds.isEmpty() ? null : speeds,
                punch,
                t.progressBar(),
                t.lutKey(),
                true);
    }

    /** The test render's size: the trim's own size shrunk (keeping its shape) until its short side is SAMPLE_SIDE. */
    static MediaTools.ScaleSize sampleScale(EditJobParams.Trim t, Integer videoW, Integer videoH) {
        int w;
        int h;
        if (t.scale() != null) {
            w = t.scale().w();
            h = t.scale().h();
        } else {
            if (videoW == null || videoH == null) {
                return null;
            }
            if (t.reframe() != null) {
                AutoCropRules.CropRect box = AutoCropRules.suggestCrop(t.reframe().aspect(), videoW, videoH, 0.5, 0.5);
                w = box.w();
                h = box.h();
            } else if (t.crop() != null) {
                w = t.crop().w();
                h = t.crop().h();
            } else {
                w = videoW;
                h = videoH;
            }
            if (swapsSides(t.rotate())) {
                int side = w;
                w = h;
                h = side;
            }
        }
        int shortSide = Math.min(w, h);
        if (shortSide <= SAMPLE_SIDE) {
            return t.scale();
        }
        double f = (double) SAMPLE_SIDE / shortSide;
        return new MediaTools.ScaleSize(
                Math.max(2, (int) Math.round(w * f / 2) * 2), Math.max(2, (int) Math.round(h * f / 2) * 2));
    }
}
