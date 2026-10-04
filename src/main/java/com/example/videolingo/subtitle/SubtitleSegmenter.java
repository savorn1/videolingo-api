package com.example.videolingo.subtitle;

import java.util.ArrayList;
import java.util.List;

// Turns transcript segments into readable subtitle cues:
//   1. a segment too long for one cue is split into chunks — preferably after
//      punctuation, otherwise between words (or characters, for spaceless scripts);
//   2. each chunk gets a share of the segment's time proportional to its length;
//   3. each cue's text is wrapped into balanced lines;
//   4. cues shorter than the minimum are stretched into the gap after them,
//      never into the next cue.
// Deterministic and dependency-free, so it runs inline (no processing job).
public final class SubtitleSegmenter {

    private SubtitleSegmenter() {}

    public static List<Cue> segment(List<Cue> segments, SubtitleRules rules) {
        List<Cue> cues = new ArrayList<>();
        for (Cue segment : segments) {
            String text = SubtitleText.normalizeSpace(segment.text());
            if (text.isEmpty() || segment.endMs() <= segment.startMs()) {
                continue;
            }
            List<String> chunks = chunk(text, rules);
            int totalChars =
                    chunks.stream().mapToInt(SubtitleText::readableLength).sum();
            long duration = segment.endMs() - segment.startMs();
            long cursor = segment.startMs();
            int charsSoFar = 0;
            for (int i = 0; i < chunks.size(); i++) {
                charsSoFar += SubtitleText.readableLength(chunks.get(i));
                long end = i == chunks.size() - 1
                        ? segment.endMs()
                        : segment.startMs() + Math.round((double) duration * charsSoFar / Math.max(1, totalChars));
                cues.add(new Cue(cursor, Math.max(end, cursor + 1), wrap(chunks.get(i), rules)));
                cursor = end;
            }
        }
        cues.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return stretchShortCues(cues, rules);
    }

    // Greedy packing into ≤ maxCharsPerCue chunks, cutting at the last clause
    // boundary when that keeps the chunk at least half full.
    static List<String> chunk(String text, SubtitleRules rules) {
        int limit = rules.maxCharsPerCue();
        if (SubtitleText.length(text) <= limit) {
            return List.of(text);
        }
        boolean spaceless = SubtitleText.isSpaceless(text);
        List<String> tokens = tokens(text, spaceless);
        String joiner = spaceless ? "" : " ";

        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            List<String> candidate = new ArrayList<>(current);
            candidate.add(token);
            if (current.isEmpty() || SubtitleText.length(String.join(joiner, candidate)) <= limit) {
                current = candidate;
                continue;
            }
            int cut = bestCut(current, joiner, limit);
            chunks.add(String.join(joiner, current.subList(0, cut)));
            List<String> rest = new ArrayList<>(current.subList(cut, current.size()));
            rest.add(token);
            current = rest;
        }
        if (!current.isEmpty()) {
            chunks.add(String.join(joiner, current));
        }
        // A single word longer than a whole cue can't be helped; it's kept intact.
        return chunks;
    }

    // Where to end a full chunk: after the last sentence end that leaves the
    // chunk at least a third full, else after the last clause break (comma etc.)
    // that leaves it half full, else at the limit. Ending a cue mid-sentence —
    // or, in Japanese, mid-word — is what makes subtitles hard to read.
    private static int bestCut(List<String> current, String joiner, int limit) {
        for (int i = current.size() - 1; i > 0; i--) {
            if (SubtitleText.endsSentence(current.get(i - 1))
                    && SubtitleText.length(String.join(joiner, current.subList(0, i))) * 3 >= limit) {
                return i;
            }
        }
        for (int i = current.size() - 1; i > 0; i--) {
            if (SubtitleText.endsClause(current.get(i - 1))
                    && SubtitleText.length(String.join(joiner, current.subList(0, i))) * 2 >= limit) {
                return i;
            }
        }
        return current.size();
    }

    private static List<String> tokens(String text, boolean spaceless) {
        List<String> out = new ArrayList<>();
        if (!spaceless) {
            for (String word : text.split(" ")) {
                if (!word.isEmpty()) {
                    out.add(word);
                }
            }
            return out;
        }
        // Spaceless: one token per character, but keep spaces (mixed text such
        // as "Tokyo タワー") and glue closing punctuation to what precedes it.
        text.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (!out.isEmpty() && (SubtitleText.endsClause(ch) || ch.equals(" "))) {
                out.set(out.size() - 1, out.get(out.size() - 1) + ch);
            } else {
                out.add(ch);
            }
        });
        return out;
    }

    // Balanced line breaks: among the break points that keep every line within
    // the limit, pick the one whose lines are closest in length (for 2 lines);
    // for more lines, wrap greedily.
    static String wrap(String text, SubtitleRules rules) {
        int max = rules.maxCharsPerLine();
        if (SubtitleText.length(text) <= max || rules.maxLines() == 1) {
            return text;
        }
        boolean spaceless = SubtitleText.isSpaceless(text);
        List<Integer> breaks = new ArrayList<>();
        for (int i = 1; i < text.length(); i++) {
            if (spaceless
                    ? !Character.isLowSurrogate(text.charAt(i))
                            && !SubtitleText.endsClause(String.valueOf(text.charAt(i)))
                    : text.charAt(i) == ' ') {
                breaks.add(i);
            }
        }
        if (rules.maxLines() == 2 || SubtitleText.length(text) <= max * 2) {
            int best = -1;
            int bestScore = Integer.MAX_VALUE;
            for (int b : breaks) {
                String left = text.substring(0, b).strip();
                String right = text.substring(b).strip();
                int l = SubtitleText.length(left);
                int r = SubtitleText.length(right);
                if (l > max || r > max) {
                    continue;
                }
                // Prefer balanced lines; nudge toward breaking after punctuation.
                int score = Math.abs(l - r) * 2 - (SubtitleText.endsClause(left) ? 6 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best > 0) {
                return text.substring(0, best).strip() + "\n"
                        + text.substring(best).strip();
            }
        }
        // Greedy fallback (more than two lines, or no balanced split fits).
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (String token : tokens(text, spaceless)) {
            String sep = line.isEmpty() || spaceless ? "" : " ";
            if (SubtitleText.length(line + sep + token) > max && !line.isEmpty()) {
                out.append(line.toString().strip()).append('\n');
                line.setLength(0);
                sep = "";
            }
            line.append(sep).append(token);
        }
        out.append(line.toString().strip());
        return out.toString();
    }

    private static List<Cue> stretchShortCues(List<Cue> cues, SubtitleRules rules) {
        List<Cue> out = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            long wanted = c.startMs() + rules.minDurationMs();
            if (c.endMs() < wanted) {
                long limit = i + 1 < cues.size() ? cues.get(i + 1).startMs() : Long.MAX_VALUE;
                c = new Cue(c.startMs(), Math.max(c.endMs(), Math.min(wanted, limit)), c.text());
            }
            out.add(c);
        }
        return out;
    }
}
