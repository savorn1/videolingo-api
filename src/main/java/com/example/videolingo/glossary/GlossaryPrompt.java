package com.example.videolingo.glossary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Turns glossary rules into translation-prompt instructions. Pure and static
// so it's unit-tested; the frontend's shared/utils/glossary.ts mirrors
// `contains` for the subtitle editor's warnings.
public final class GlossaryPrompt {

    // Per batch — only terms that actually occur in the batch are sent, so
    // this only bites on a batch that mentions hundreds of distinct terms.
    static final int MAX_TERMS = 200;

    private GlossaryPrompt() {
    }

    /** The terms whose source text occurs somewhere in `lines`, first occurrence of each source wins. */
    public static List<GlossaryEntry> relevant(List<GlossaryEntry> terms, List<String> lines) {
        if (terms.isEmpty() || lines.isEmpty()) {
            return List.of();
        }
        String text = String.join("\n", lines);
        Map<String, GlossaryEntry> bySource = new LinkedHashMap<>();
        for (GlossaryEntry t : terms) {
            String key = t.source().toLowerCase(Locale.ROOT);
            if (!bySource.containsKey(key) && contains(text, t.source(), t.caseSensitive())) {
                bySource.put(key, t);
            }
        }
        List<GlossaryEntry> out = new ArrayList<>(bySource.values());
        return out.size() > MAX_TERMS ? out.subList(0, MAX_TERMS) : out;
    }

    /** Prompt instructions for `terms`, or "" when there are none. */
    public static String section(List<GlossaryEntry> terms) {
        if (terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("- Follow this glossary exactly, even where another wording would read more naturally:\n");
        for (GlossaryEntry t : terms) {
            sb.append("  - ");
            if (t.doNotTranslate()) {
                sb.append("Keep \"").append(t.source()).append("\" as it is — don't translate or transliterate it");
            } else {
                sb.append('"').append(t.source()).append("\" → \"").append(t.target()).append('"');
            }
            if (t.note() != null && !t.note().isBlank()) {
                sb.append(" (").append(t.note().strip()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Whether `term` occurs in `text` as a whole word. The word-boundary check
     * only applies at an end of the term written in a spaced script (Latin,
     * Cyrillic, Greek, digits): Khmer, Thai, Japanese and Chinese don't put
     * spaces between words, so there any substring match counts.
     */
    public static boolean contains(String text, String term, boolean caseSensitive) {
        if (term == null || term.isBlank() || text == null) {
            return false;
        }
        String hay = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        String needle = caseSensitive ? term.strip() : term.strip().toLowerCase(Locale.ROOT);
        boolean checkStart = isSpaced(needle.codePointAt(0));
        boolean checkEnd = isSpaced(needle.codePointBefore(needle.length()));
        for (int from = hay.indexOf(needle); from >= 0; from = hay.indexOf(needle, from + 1)) {
            int end = from + needle.length();
            boolean startOk = !checkStart || from == 0 || !Character.isLetterOrDigit(hay.codePointBefore(from));
            boolean endOk = !checkEnd || end == hay.length() || !Character.isLetterOrDigit(hay.codePointAt(end));
            if (startOk && endOk) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpaced(int codePoint) {
        if (Character.isDigit(codePoint)) {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.CYRILLIC
                || script == Character.UnicodeScript.GREEK;
    }
}
