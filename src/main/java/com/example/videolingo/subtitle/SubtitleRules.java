package com.example.videolingo.subtitle;

// Readability limits a subtitle track is built to and checked against.
// Defaults follow common broadcast/streaming guidance (≈42 chars × 2 lines,
// ≤17 characters per second, on screen for 1–7 s).
public record SubtitleRules(int maxCharsPerLine, int maxLines, long minDurationMs, long maxDurationMs, double maxCps) {

    public static final SubtitleRules DEFAULT = new SubtitleRules(42, 2, 1000, 7000, 17.0);

    public SubtitleRules {
        if (maxCharsPerLine < 10 || maxLines < 1 || minDurationMs < 0 || maxDurationMs <= minDurationMs || maxCps <= 0) {
            throw new IllegalArgumentException("Invalid subtitle rules");
        }
    }

    // Chinese and Japanese pack far more meaning per character, so guidelines
    // for them use much shorter lines and a lower reading speed (≈16 full-width
    // characters per line, ≈9 per second). Only the starting point for a new
    // track — every limit is editable per track.
    public static final SubtitleRules CJK = new SubtitleRules(16, 2, 1000, 7000, 9.0);

    public static SubtitleRules defaultsFor(String language) {
        if (language == null) {
            return DEFAULT;
        }
        String primary = language.split("-")[0].toLowerCase();
        return primary.equals("ja") || primary.equals("zh") ? CJK : DEFAULT;
    }

    public int maxCharsPerCue() {
        return maxCharsPerLine * maxLines;
    }
}
