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

    public record Segment(long startMs, Long endMs) {
    }

    private VideoEditRules() {
    }

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
        return degrees == null || ROTATIONS.contains(degrees) ? null : "The picture can be turned 90, 180 or 270 degrees";
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
