package com.example.videolingo.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Checks cues against the track's rules. Warnings, not errors — a track with
// issues can still be saved and published; the UI just flags them.
public final class SubtitleQuality {

    private SubtitleQuality() {}

    public static List<SubtitleIssue> check(List<Cue> cues, SubtitleRules rules) {
        List<SubtitleIssue> issues = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            String[] lines = c.text().split("\n", -1);
            if (lines.length > rules.maxLines()) {
                issues.add(new SubtitleIssue(
                        i, SubtitleIssue.Type.TOO_MANY_LINES, lines.length + " lines (max " + rules.maxLines() + ")"));
            }
            for (String line : lines) {
                int len = SubtitleText.length(line);
                if (len > rules.maxCharsPerLine()) {
                    issues.add(new SubtitleIssue(
                            i,
                            SubtitleIssue.Type.LINE_TOO_LONG,
                            "Line has " + len + " characters (max " + rules.maxCharsPerLine() + ")"));
                    break;
                }
            }
            long duration = c.endMs() - c.startMs();
            if (duration > 0) {
                double cps = SubtitleText.readableLength(c.text()) * 1000.0 / duration;
                if (cps > rules.maxCps()) {
                    issues.add(new SubtitleIssue(
                            i,
                            SubtitleIssue.Type.TOO_FAST,
                            String.format(Locale.ROOT, "%.1f characters/second (max %.0f)", cps, rules.maxCps())));
                }
            }
            if (duration < rules.minDurationMs()) {
                issues.add(new SubtitleIssue(
                        i,
                        SubtitleIssue.Type.TOO_SHORT,
                        String.format(
                                Locale.ROOT,
                                "On screen %.1fs (min %.1fs)",
                                duration / 1000.0,
                                rules.minDurationMs() / 1000.0)));
            } else if (duration > rules.maxDurationMs()) {
                issues.add(new SubtitleIssue(
                        i,
                        SubtitleIssue.Type.TOO_LONG,
                        String.format(
                                Locale.ROOT,
                                "On screen %.1fs (max %.1fs)",
                                duration / 1000.0,
                                rules.maxDurationMs() / 1000.0)));
            }
            if (i + 1 < cues.size() && cues.get(i + 1).startMs() < c.endMs()) {
                issues.add(new SubtitleIssue(i, SubtitleIssue.Type.OVERLAP, "Overlaps the next cue"));
            }
        }
        return issues;
    }
}
