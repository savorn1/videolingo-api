package com.example.videolingo.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Subtitles burned in from a transcript: pairs a second transcript (a translation) with the first,
// and writes an ASS file for ffmpeg's subtitles filter (libass), which gives the outline, box,
// colour and placement. Pure, so it's unit-tested.
public final class CaptionRules {

    /** A word of a line with its own time (from speech-to-text), for karaoke. */
    public record Word(long startMs, long endMs, String text) {}

    /**
     * One subtitle: its time on the video and its text (`second` = the translation line, or null); `words` = the
     * timed words of `text`, when known.
     */
    public record Cue(long startMs, long endMs, String text, String second, List<Word> words) {
        public Cue(long startMs, long endMs, String text, String second) {
            this(startMs, endMs, text, second, null);
        }
    }

    /** A transcript line as read from the database, with its timed words when speech-to-text gave them. */
    public record Line(long startMs, long endMs, String text, List<Word> words) {
        public Line(long startMs, long endMs, String text) {
            this(startMs, endMs, text, null);
        }
    }

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
            cues.add(new Cue(a.startMs(), a.endMs(), a.text().strip(), paired, a.words()));
        }
        return cues;
    }

    /**
     * A look of one's own over the style: colours as #RRGGBB (the second line's, and the outline's or box's), a box
     * behind the text (with how solid it is, 0–1) instead of an outline, and bold or not.
     */
    public record Look(
            String color, String secondColor, String edgeColor, boolean box, double boxOpacity, boolean bold) {}

    /** Null when the look is usable (or absent), else why not. */
    public static String validateLook(Look l) {
        if (l == null) {
            return null;
        }
        java.util.regex.Pattern hex = java.util.regex.Pattern.compile("#[0-9a-fA-F]{6}");
        for (String c : java.util.Arrays.asList(l.color(), l.secondColor(), l.edgeColor())) {
            if (c == null || !hex.matcher(c).matches()) {
                return "Subtitles: pick the colours";
            }
        }
        if (l.boxOpacity() < 0 || l.boxOpacity() > 1) {
            return "Subtitles: the box's opacity is between 0 and 100%";
        }
        return null;
    }

    /** #RRGGBB as an ASS colour (&HAABBGGRR), `opacity` 1 = solid. */
    static String assColour(String hex, double opacity) {
        int alpha = (int) Math.round(255 * (1 - Math.min(1, Math.max(0, opacity))));
        return String.format(
                Locale.ROOT,
                "&H%02X%s%s%s",
                alpha,
                hex.substring(5, 7).toUpperCase(Locale.ROOT),
                hex.substring(3, 5).toUpperCase(Locale.ROOT),
                hex.substring(1, 3).toUpperCase(Locale.ROOT));
    }

    /** An ASS file of the cues, laid out for a `width`×`height` video. */
    public static String toAss(List<Cue> cues, String style, String position, double sizePct, int width, int height) {
        return toAss(cues, style, position, sizePct, width, height, null);
    }

    /** The same, with a look of one's own (null: the style's). */
    public static String toAss(
            List<Cue> cues, String style, String position, double sizePct, int width, int height, Look look) {
        int size = Math.max(8, (int) Math.round(height * sizePct / 100));
        int secondSize = Math.max(8, (int) Math.round(size * 0.8));
        int margin = (int) Math.round(height * 0.06);
        int sideMargin = (int) Math.round(width * 0.05);
        int alignment = "TOP".equals(position) ? 8 : 2;
        boolean box = "BOX".equals(style);
        boolean yellow = "YELLOW".equals(style);
        boolean karaoke = "KARAOKE".equals(style);
        // Colours are &HAABBGGRR (AA = transparency). Karaoke fills each word from the secondary colour (white,
        // not yet said) to the primary one (gold) as it is said.
        String main = yellow ? "&H0000FFFF" : karaoke ? "&H0000D7FF" : "&H00FFFFFF";
        // Only karaoke uses the secondary colour; the other styles keep the one they always had.
        String before = karaoke ? "&H00FFFFFF" : "&H000000FF";
        String other = yellow ? "&H00FFFFFF" : "&H0099FFFF";
        String edge = box ? "&H80000000" : "&H00000000";
        boolean bold = true;
        if (look != null) {
            box = look.box();
            main = assColour(look.color(), 1);
            other = assColour(look.secondColor(), 1);
            edge = assColour(look.edgeColor(), box ? look.boxOpacity() : 1);
            bold = look.bold();
            if (karaoke) {
                before = assColour(look.secondColor(), 1);
            }
        }
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
        out.append(style("Main", size, main, before, edge, borderStyle, outline, alignment, sideMargin, margin, bold));
        out.append(style(
                "Second", secondSize, other, before, edge, borderStyle, outline, alignment, sideMargin, margin, bold));
        out.append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (Cue c : cues) {
            String text = karaoke ? karaokeText(c) : escape(c.text());
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
            String secondary,
            String edge,
            int borderStyle,
            int outline,
            int align,
            int side,
            int v,
            boolean bold) {
        return String.format(
                Locale.ROOT,
                "Style: %s,Noto Sans,%d,%s,%s,%s,&H80000000,%d,0,0,0,100,100,0,0,%d,%d,0,%d,%d,%d,%d,1%n",
                name,
                size,
                colour,
                secondary,
                edge,
                bold ? -1 : 0,
                borderStyle,
                outline,
                align,
                side,
                side,
                v);
    }

    /**
     * The line as karaoke: each word filled in over its own time ({\kf}, in centiseconds), the wait before the first
     * one as an empty step. Without timed words, the words share the line's time by their length.
     */
    static String karaokeText(Cue c) {
        List<Word> words = c.words() != null && !c.words().isEmpty() ? c.words() : spreadWords(c);
        StringBuilder out = new StringBuilder();
        long lead = words.get(0).startMs() - c.startMs();
        if (lead >= 10) {
            out.append("{\\k").append(lead / 10).append('}');
        }
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            long end = i + 1 < words.size() ? words.get(i + 1).startMs() : Math.max(w.endMs(), c.endMs());
            out.append("{\\kf")
                    .append(Math.max(1, (end - w.startMs()) / 10))
                    .append('}')
                    .append(escape(w.text()));
            if (i + 1 < words.size()) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /** The line's words spread over its time, each in proportion to its length. */
    static List<Word> spreadWords(Cue c) {
        String[] parts = c.text().strip().split("\\s+");
        long letters = java.util.Arrays.stream(parts).mapToLong(String::length).sum();
        long span = Math.max(1, c.endMs() - c.startMs());
        List<Word> out = new ArrayList<>();
        long at = c.startMs();
        long used = 0;
        for (String part : parts) {
            used += part.length();
            long end = c.startMs() + span * used / Math.max(1, letters);
            out.add(new Word(at, end, part));
            at = end;
        }
        return out;
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
