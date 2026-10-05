package com.example.videolingo.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Subtitles burned in from a transcript: pairs a second transcript (a translation) with the first,
// and writes an ASS file for ffmpeg's subtitles filter (libass), which gives the outline, box,
// colour and placement. Pure, so it's unit-tested.
public final class CaptionRules {

    /** One subtitle: its time on the video and its text (`second` = the translation line, or null). */
    public record Cue(long startMs, long endMs, String text, String second) {}

    /** A transcript line as read from the database. */
    public record Line(long startMs, long endMs, String text) {}

    private CaptionRules() {}

    /**
     * The first transcript's lines, each with the second transcript's text that belongs to it: the lines
     * mostly inside it (by at least half their length), or else the one overlapping it the most.
     * Empty lines are left out.
     */
    public static List<Cue> pair(List<Line> first, List<Line> second) {
        List<Cue> cues = new ArrayList<>();
        for (Line a : first) {
            if (a.text() == null || a.text().isBlank() || a.endMs() <= a.startMs()) {
                continue;
            }
            String paired = null;
            if (second != null && !second.isEmpty()) {
                List<String> inside = new ArrayList<>();
                Line best = null;
                long bestOverlap = 0;
                for (Line b : second) {
                    if (b.text() == null || b.text().isBlank()) {
                        continue;
                    }
                    long overlap = Math.min(a.endMs(), b.endMs()) - Math.max(a.startMs(), b.startMs());
                    if (overlap <= 0) {
                        continue;
                    }
                    if (overlap * 2 >= b.endMs() - b.startMs()) {
                        inside.add(b.text().strip());
                    }
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap;
                        best = b;
                    }
                }
                paired = !inside.isEmpty()
                        ? String.join(" ", inside)
                        : best != null ? best.text().strip() : null;
            }
            cues.add(new Cue(a.startMs(), a.endMs(), a.text().strip(), paired));
        }
        return cues;
    }

    /** An ASS file of the cues, laid out for a `width`×`height` video. */
    public static String toAss(List<Cue> cues, String style, String position, double sizePct, int width, int height) {
        int size = Math.max(8, (int) Math.round(height * sizePct / 100));
        int secondSize = Math.max(8, (int) Math.round(size * 0.8));
        int margin = (int) Math.round(height * 0.06);
        int sideMargin = (int) Math.round(width * 0.05);
        int alignment = "TOP".equals(position) ? 8 : 2;
        boolean box = "BOX".equals(style);
        boolean yellow = "YELLOW".equals(style);
        // Colours are &HAABBGGRR (AA = transparency).
        String main = yellow ? "&H0000FFFF" : "&H00FFFFFF";
        String other = yellow ? "&H00FFFFFF" : "&H0099FFFF";
        String edge = box ? "&H80000000" : "&H00000000";
        int borderStyle = box ? 3 : 1;
        int outline = box ? Math.max(2, size / 5) : Math.max(1, size / 14);

        StringBuilder out = new StringBuilder();
        out.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ")
                .append(width)
                .append("\nPlayResY: ")
                .append(height)
                .append("\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n");
        out.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
                + "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, "
                + "Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        out.append(style("Main", size, main, edge, borderStyle, outline, alignment, sideMargin, margin));
        out.append(style("Second", secondSize, other, edge, borderStyle, outline, alignment, sideMargin, margin));
        out.append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (Cue c : cues) {
            String text = escape(c.text());
            if (c.second() != null && !c.second().isBlank()) {
                text += "\\N{\\rSecond}" + escape(c.second());
            }
            out.append("Dialogue: 0,")
                    .append(time(c.startMs()))
                    .append(',')
                    .append(time(c.endMs()))
                    .append(",Main,,0,0,0,,")
                    .append(text)
                    .append('\n');
        }
        return out.toString();
    }

    private static String style(
            String name,
            int size,
            String colour,
            String edge,
            int borderStyle,
            int outline,
            int align,
            int side,
            int v) {
        return String.format(
                Locale.ROOT,
                "Style: %s,Noto Sans,%d,%s,&H000000FF,%s,&H80000000,-1,0,0,0,100,100,0,0,%d,%d,0,%d,%d,%d,%d,1%n",
                name,
                size,
                colour,
                edge,
                borderStyle,
                outline,
                align,
                side,
                side,
                v);
    }

    /** Text as an ASS event: braces and backslashes would start override codes, so they become look-alikes. */
    static String escape(String text) {
        return text.strip()
                .replace("\\", "＼")
                .replace("{", "｛")
                .replace("}", "｝")
                .replaceAll("\\r?\\n", "\\\\N");
    }

    /** H:MM:SS.cc */
    static String time(long ms) {
        long cs = Math.max(0, ms) / 10;
        return String.format(
                Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100);
    }
}
