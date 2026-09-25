package com.example.videolingo.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reading and writing SRT / WebVTT.
public final class SubtitleFiles {

    private SubtitleFiles() {
    }

    public record Parsed(String format, List<Cue> cues, List<String> warnings) {
    }

    private static final Pattern TIMING = Pattern.compile("^\\s*(\\S+)\\s+-->\\s+(\\S+)");
    private static final Pattern TIMESTAMP = Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})$");

    // Returns null for anything that isn't a valid SRT/VTT timestamp.
    static Long parseTimestamp(String raw) {
        Matcher m = TIMESTAMP.matcher(raw.strip());
        if (!m.matches()) {
            return null;
        }
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long min = Long.parseLong(m.group(2));
        long s = Long.parseLong(m.group(3));
        String frac = (m.group(4) + "00").substring(0, 3);
        if (min >= 60 || s >= 60) {
            return null;
        }
        return ((h * 60 + min) * 60 + s) * 1000 + Long.parseLong(frac);
    }

    /**
     * Parses SRT or WebVTT. Line breaks inside a cue are kept; markup (&lt;i&gt;,
     * &lt;c.x&gt;, voice tags, inline timestamps, {\an8}) is stripped. Broken cues
     * are skipped and reported in `warnings` rather than failing the upload.
     */
    public static Parsed parse(String content) {
        String text = content.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        boolean vtt = text.stripLeading().startsWith("WEBVTT");
        if (!vtt && !text.contains("-->")) {
            throw new IllegalArgumentException("Not a subtitle file — no SRT or WebVTT cue timings found");
        }
        List<Cue> cues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int cueNumber = 0;
        for (String block : text.split("\n{2,}")) {
            String[] lines = block.strip().split("\n");
            if (lines.length == 0 || lines[0].matches("^(WEBVTT|NOTE|STYLE|REGION)\\b.*")) {
                continue;
            }
            int timingLine = -1;
            for (int i = 0; i < lines.length; i++) {
                if (TIMING.matcher(lines[i]).find()) {
                    timingLine = i;
                    break;
                }
            }
            if (timingLine < 0) {
                continue;
            }
            cueNumber++;
            Matcher t = TIMING.matcher(lines[timingLine]);
            t.find();
            Long start = parseTimestamp(t.group(1));
            Long end = parseTimestamp(t.group(2));
            List<String> textLines = new ArrayList<>();
            for (int i = timingLine + 1; i < lines.length; i++) {
                String clean = cleanLine(lines[i]);
                if (!clean.isEmpty()) {
                    textLines.add(clean);
                }
            }
            if (start == null || end == null) {
                warnings.add("Cue " + cueNumber + " skipped: unreadable timing \"" + lines[timingLine].strip() + "\"");
            } else if (end <= start) {
                warnings.add("Cue " + cueNumber + " skipped: it ends before it starts");
            } else if (textLines.isEmpty()) {
                warnings.add("Cue " + cueNumber + " skipped: no text");
            } else {
                cues.add(new Cue(start, end, String.join("\n", textLines)));
            }
        }
        cues.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return new Parsed(vtt ? "vtt" : "srt", cues, warnings);
    }

    private static String cleanLine(String line) {
        return line.replaceAll("<[^>]*>", "")
                .replaceAll("\\{\\\\[^}]*}", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .strip();
    }

    public static String timestamp(long ms, char fractionSeparator) {
        return String.format("%02d:%02d:%02d%c%03d", ms / 3_600_000, (ms % 3_600_000) / 60_000, (ms % 60_000) / 1000, fractionSeparator, ms % 1000);
    }

    public static String toSrt(List<Cue> cues) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            out.append(i + 1).append('\n')
                    .append(timestamp(c.startMs(), ',')).append(" --> ").append(timestamp(c.endMs(), ',')).append('\n')
                    .append(c.text()).append("\n\n");
        }
        return out.toString();
    }

    public static String toVtt(List<Cue> cues) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (Cue c : cues) {
            out.append(timestamp(c.startMs(), '.')).append(" --> ").append(timestamp(c.endMs(), '.')).append('\n')
                    // "-->" can't appear in VTT cue text; & and < must be escaped.
                    .append(c.text().replace("&", "&amp;").replace("<", "&lt;").replace("-->", "--&gt;")).append("\n\n");
        }
        return out.toString();
    }
}
