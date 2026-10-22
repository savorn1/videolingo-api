package com.example.videolingo.pipeline;

import java.util.List;

// "Make a highlight clip" (Video from audio): the liveliest stretch of a recording, for a short tall video.
// Lively = loud on average with few silent seconds; the ends are moved to the nearest pause so the clip
// doesn't start or stop mid-sentence. Pure, so it's unit-tested; the levels come from MediaTools.levels.
public final class HighlightRules {

    private HighlightRules() {}

    public static final int MIN_SECONDS = 15;
    public static final int MAX_SECONDS = 90;
    /** A second this quiet (RMS, dB) counts as silent. */
    static final double SILENT_DB = -45;
    /** How far an end may move to reach a pause. */
    static final long SNAP_MS = 4000;

    public record Range(long startMs, long endMs) {}

    /**
     * The best `lengthMs` stretch of a recording `totalMs` long, from its level in each second (`levelsDb`, -∞
     * allowed) and its pauses. The whole recording when it is no longer than the clip.
     */
    public static Range best(
            List<Double> levelsDb, List<AudioToVideoRules.Silence> silences, long totalMs, long lengthMs) {
        if (totalMs <= lengthMs || levelsDb.isEmpty()) {
            return new Range(0, Math.min(totalMs, lengthMs));
        }
        int n = levelsDb.size();
        int w = (int) Math.max(1, Math.min(n, lengthMs / 1000));
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            double db = levelsDb.get(i);
            double level = Double.isFinite(db) ? Math.max(-90, db) : -90;
            // Loud is lively; a silent second costs extra, so a stretch full of gaps loses.
            score[i] = level + (level < SILENT_DB ? -30 : 0);
        }
        double sum = 0;
        for (int i = 0; i < w; i++) {
            sum += score[i];
        }
        double bestSum = sum;
        int bestStart = 0;
        for (int i = w; i < n; i++) {
            sum += score[i] - score[i - w];
            if (sum > bestSum) {
                bestSum = sum;
                bestStart = i - w + 1;
            }
        }
        long start = Math.min(bestStart * 1000L, totalMs - lengthMs);
        long end = start + lengthMs;
        // Start just after a pause, and stop in one, when there is one near.
        Long after = nearest(silences, start, true);
        if (after != null) {
            start = after;
        }
        Long stop = nearest(silences, start + lengthMs, false);
        end = stop != null && stop > start + lengthMs / 2 ? stop : Math.min(totalMs, start + lengthMs);
        return new Range(Math.max(0, start), Math.min(totalMs, end));
    }

    /** The end (`startEdge`) or middle of the pause nearest `atMs`, within SNAP_MS; null when none is that close. */
    private static Long nearest(List<AudioToVideoRules.Silence> silences, long atMs, boolean startEdge) {
        Long best = null;
        long bestDistance = SNAP_MS + 1;
        for (AudioToVideoRules.Silence s : silences) {
            long point = startEdge ? s.endMs() : (s.startMs() + s.endMs()) / 2;
            long d = Math.abs(point - atMs);
            if (d < bestDistance) {
                bestDistance = d;
                best = point;
            }
        }
        return best;
    }

    /** The per-second RMS levels in ffmpeg astats/ametadata output ("lavfi.astats.Overall.RMS_level=-23.4"). */
    public static List<Double> parseLevels(String log) {
        List<Double> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "lavfi\\.astats\\.Overall\\.RMS_level=(-?inf|-?[0-9.]+)")
                .matcher(log);
        while (m.find()) {
            String v = m.group(1);
            out.add(v.endsWith("inf") ? Double.NEGATIVE_INFINITY : Double.parseDouble(v));
        }
        return out;
    }
}
