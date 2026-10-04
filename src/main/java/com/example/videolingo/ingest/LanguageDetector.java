package com.example.videolingo.ingest;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Best-guess spoken language from a video's title and description. Scripts
// that belong to one language (Khmer, Thai, Hangul, kana…) decide on their
// own; Latin text is scored against short lists of very common words.
// Deliberately conservative: returns null rather than guess from too little.
public final class LanguageDetector {

    /** {@code confidence} is 0–1. */
    public record Guess(String code, double confidence) {}

    private static final Map<String, Set<String>> STOPWORDS = Map.ofEntries(
            Map.entry(
                    "en",
                    Set.of(
                            "the", "and", "you", "to", "of", "is", "in", "it", "for", "with", "this", "that", "how",
                            "what", "are", "my", "your", "i", "we", "on", "a", "an", "be", "learn", "can", "do", "at",
                            "from")),
            Map.entry(
                    "fr",
                    Set.of(
                            "le", "la", "les", "des", "et", "est", "une", "un", "pour", "dans", "avec", "que", "qui",
                            "pas", "vous", "je", "nous", "du", "sur", "au", "ce", "comment", "sont", "mon", "votre")),
            Map.entry(
                    "es",
                    Set.of(
                            "el", "la", "los", "las", "y", "es", "una", "un", "para", "en", "con", "que", "por", "no",
                            "del", "como", "más", "mi", "tu", "se", "lo", "al", "está", "cómo", "qué")),
            Map.entry(
                    "de",
                    Set.of(
                            "der", "die", "das", "und", "ist", "ein", "eine", "nicht", "mit", "für", "auf", "den",
                            "dem", "zu", "ich", "sie", "wir", "wie", "was", "auch", "im", "von", "sich", "mein", "du")),
            Map.entry(
                    "it",
                    Set.of(
                            "il", "lo", "la", "gli", "le", "e", "è", "un", "una", "per", "con", "che", "non", "di",
                            "del", "della", "come", "sono", "mio", "io", "noi", "nel", "questo")),
            Map.entry(
                    "pt",
                    Set.of(
                            "o", "a", "os", "as", "e", "é", "um", "uma", "para", "com", "que", "não", "do", "da", "dos",
                            "como", "em", "no", "na", "eu", "você", "meu", "está", "são")),
            Map.entry(
                    "nl",
                    Set.of(
                            "de", "het", "een", "en", "is", "van", "voor", "met", "niet", "dat", "ik", "je", "we", "op",
                            "hoe", "wat", "zijn", "mijn")),
            Map.entry(
                    "id",
                    Set.of(
                            "dan", "yang", "di", "ke", "dari", "ini", "itu", "dengan", "untuk", "tidak", "saya", "kamu",
                            "kita", "ada", "cara", "belajar", "bahasa", "apa")),
            Map.entry(
                    "vi",
                    Set.of(
                            "và", "của", "là", "có", "không", "cho", "người", "những", "một", "các", "với", "này",
                            "được", "trong", "tôi", "bạn", "học", "tiếng", "cách")),
            Map.entry(
                    "tr",
                    Set.of(
                            "ve", "bir", "bu", "için", "ile", "da", "de", "ne", "nasıl", "çok", "ben", "sen", "biz",
                            "var", "yok", "değil")),
            Map.entry(
                    "pl",
                    Set.of(
                            "i", "w", "na", "z", "nie", "to", "jest", "się", "jak", "do", "że", "co", "ja", "ty", "czy",
                            "dla")));

    // Letters that only one of the Latin languages above uses.
    private static final Map<String, String> MARKERS = Map.of(
            "vi", "ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ",
            "de", "ßäöü",
            "es", "ñ¿¡",
            "pt", "ãõ",
            "tr", "ğış",
            "pl", "ąęłńśźż",
            "fr", "œç");

    private LanguageDetector() {}

    public static Guess detect(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(' ').append(stripNoise(p));
            }
        }
        String text = sb.toString();
        Guess byScript = byScript(text);
        if (byScript != null) {
            return byScript;
        }
        return byWords(text.toLowerCase(Locale.ROOT));
    }

    // URLs, hashtags, @handles and emails say nothing about the language.
    private static String stripNoise(String s) {
        return s.replaceAll("https?://\\S+", " ").replaceAll("[#@]\\S+", " ").replaceAll("\\S+@\\S+", " ");
    }

    private static Guess byScript(String text) {
        Map<String, Integer> counts = new HashMap<>();
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            String code = switch (script) {
                case KHMER -> "km";
                case THAI -> "th";
                case LAO -> "lo";
                case MYANMAR -> "my";
                case HANGUL -> "ko";
                case HIRAGANA, KATAKANA -> "ja";
                case HAN -> "han";
                case ARABIC -> "ar";
                case HEBREW -> "he";
                case GREEK -> "el";
                case CYRILLIC -> "ru";
                case DEVANAGARI -> "hi";
                case BENGALI -> "bn";
                case TAMIL -> "ta";
                case TELUGU -> "te";
                case GEORGIAN -> "ka";
                case ARMENIAN -> "hy";
                default -> null;
            };
            if (code != null) {
                counts.merge(code, 1, Integer::sum);
            }
        }
        if (letters == 0 || counts.isEmpty()) {
            return null;
        }
        // Japanese text mixes kana with kanji; any real amount of kana means Japanese.
        int kana = counts.getOrDefault("ja", 0);
        int han = counts.getOrDefault("han", 0);
        if (kana > 0 && (kana + han) * 2 >= letters && kana * 10 >= han) {
            return new Guess("ja", confidence(kana + han, letters));
        }
        counts.remove("ja");
        if (han > 0) {
            counts.put("zh", counts.remove("han"));
        }
        Map.Entry<String, Integer> top =
                counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        // Only trust a script that makes up at least 40% of the letters (a Latin
        // title with one Thai word stays undecided here and goes to the word check).
        if (top.getValue() * 10 < letters * 4) {
            return null;
        }
        return new Guess(top.getKey(), confidence(top.getValue(), letters));
    }

    private static double confidence(int part, int whole) {
        return Math.min(0.99, 0.6 + 0.4 * part / (double) whole);
    }

    private static Guess byWords(String text) {
        List<String> words = List.of(text.split("[^\\p{L}']+"));
        Map<String, Double> scores = new HashMap<>();
        int counted = 0;
        for (String w : words) {
            if (w.isBlank()) {
                continue;
            }
            counted++;
            for (Map.Entry<String, Set<String>> e : STOPWORDS.entrySet()) {
                if (e.getValue().contains(w)) {
                    scores.merge(e.getKey(), 1.0, Double::sum);
                }
            }
        }
        MARKERS.forEach((code, letters) -> {
            long hits = text.chars().filter(c -> letters.indexOf(c) >= 0).count();
            if (hits > 0) {
                scores.merge(code, Math.min(4.0, hits * 1.5), Double::sum);
            }
        });
        if (counted < 3 || scores.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();
        double best = ranked.get(0).getValue();
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        if (best < 2 || best - second < 1) {
            return null; // too little signal, or a near tie
        }
        double margin = (best - second) / best;
        double coverage = Math.min(1.0, best / Math.max(4.0, counted * 0.3));
        return new Guess(ranked.get(0).getKey(), Math.round((0.35 + 0.35 * margin + 0.29 * coverage) * 100) / 100.0);
    }
}
