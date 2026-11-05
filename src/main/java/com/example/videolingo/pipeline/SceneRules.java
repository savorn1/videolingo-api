package com.example.videolingo.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "Split at scene changes": where the picture changes a lot from one frame to the next (ffmpeg's scene score), read
// from the showinfo lines of a small, fast decode; then tidied so no two cuts are closer than MIN_GAP_MS. Pure, so
// tested.
public final class SceneRules {

    private SceneRules() {}

    /** How big a change counts as a new scene, by sensitivity (ffmpeg scene score 0–1; lower finds more). */
    public static Double threshold(String sensitivity) {
        if (sensitivity == null) {
            return 0.35;
        }
        return switch (sensitivity) {
            case "LOW" -> 0.5;
            case "MEDIUM" -> 0.35;
            case "HIGH" -> 0.22;
            default -> null;
        };
    }

    /** No two scene changes kept closer than this, nor this close to the start or the end. */
    public static final long MIN_GAP_MS = 1000;

    public static final int MAX_SCENES = 200;

    /** The -vf filters: a small picture (the score barely changes, the decode is far quicker), the change, and its time. */
    static String filter(double threshold) {
        return String.format(Locale.ROOT, "scale=320:-2,select='gt(scene,%.2f)',showinfo", threshold);
    }

    private static final Pattern PTS = Pattern.compile("pts_time:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /** The times (ms) in showinfo's log lines. */
    public static List<Long> parse(String log) {
        List<Long> out = new ArrayList<>();
        for (String line : log.split("\n")) {
            if (!line.contains("showinfo")) {
                continue;
            }
            Matcher m = PTS.matcher(line);
            if (m.find()) {
                out.add(Math.round(Double.parseDouble(m.group(1)) * 1000));
            }
        }
        return out;
    }

    /** In order, at least MIN_GAP_MS from the start, the end and the one kept before; at most MAX_SCENES. */
    public static List<Long> tidy(List<Long> times, long durationMs) {
        List<Long> out = new ArrayList<>();
        long previous = 0;
        for (long t : times.stream().sorted().toList()) {
            if (t - previous < MIN_GAP_MS || (durationMs > 0 && durationMs - t < MIN_GAP_MS)) {
                continue;
            }
            out.add(t);
            previous = t;
            if (out.size() >= MAX_SCENES) {
                break;
            }
        }
        return out;
    }
}
