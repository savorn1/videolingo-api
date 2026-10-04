package com.example.videolingo.pipeline;

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

    /** A picture adjustment applied to a trim. 0 / 1 / false mean "leave it alone". */
    public record Look(
            double brightness,
            double contrast,
            double saturation,
            double blur,
            boolean grayscale,
            boolean sepia,
            boolean vignette) {

        public boolean isPlain() {
            return brightness == 0
                    && contrast == 1
                    && saturation == 1
                    && blur == 0
                    && !grayscale
                    && !sepia
                    && !vignette;
        }
    }

    /** Null = valid. Brightness −1…1, contrast 0…2, saturation 0…3, blur 0…MAX_BLUR. */
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
        if (look.grayscale()) {
            parts.add("black & white");
        } else if (look.sepia()) {
            parts.add("sepia");
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
