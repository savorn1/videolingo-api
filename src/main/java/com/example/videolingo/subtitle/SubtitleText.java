package com.example.videolingo.subtitle;

// Script-aware text helpers. Languages written without spaces between words
// (Chinese, Japanese, Thai, Khmer, Lao, Burmese) can be broken between any two
// characters; everything else only at spaces.
public final class SubtitleText {

    private SubtitleText() {}

    public static boolean isSpaceless(String text) {
        int letters = 0;
        int spaceless = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.THAI
                    || script == Character.UnicodeScript.KHMER
                    || script == Character.UnicodeScript.LAO
                    || script == Character.UnicodeScript.MYANMAR) {
                spaceless++;
            }
        }
        return letters > 0 && spaceless * 2 >= letters;
    }

    // Visible length in code points (so an emoji or CJK character counts as one).
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    // Characters that count toward reading speed: everything except whitespace.
    public static int readableLength(String text) {
        return (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
    }

    public static String normalizeSpace(String text) {
        return text.replaceAll("[\\s\\u00A0]+", " ").strip();
    }

    // Sentence-final punctuation — the best place to end a cue.
    static boolean endsSentence(String token) {
        if (token.isEmpty()) {
            return false;
        }
        int last = token.codePointBefore(token.length());
        return ".!?…。！？".indexOf(last) >= 0;
    }

    static boolean endsClause(String token) {
        if (token.isEmpty()) {
            return false;
        }
        int last = token.codePointBefore(token.length());
        return ".,!?;:…。、！？；：，".indexOf(last) >= 0;
    }
}
