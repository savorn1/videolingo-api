package com.example.videolingo.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Just enough HTML reading for video pages: <meta> tags (Open Graph, Twitter,
// itemprop) and <title>. Regex, not a DOM — pages here are only mined for tags.
final class Html {

    private static final Pattern META = Pattern.compile("<meta\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern TITLE =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);");

    private Html() {}

    /** property/name/itemprop (lower-cased) → contents, in page order. */
    static Map<String, List<String>> meta(String html) {
        Map<String, List<String>> out = new HashMap<>();
        Matcher m = META.matcher(html);
        while (m.find()) {
            Map<String, String> attrs = new HashMap<>();
            Matcher a = ATTR.matcher(m.group(1));
            while (a.find()) {
                attrs.put(a.group(1).toLowerCase(Locale.ROOT), a.group(3) != null ? a.group(3) : a.group(4));
            }
            String key = attrs.getOrDefault("property", attrs.getOrDefault("name", attrs.get("itemprop")));
            String content = attrs.get("content");
            if (key != null && content != null) {
                out.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(unescape(content).strip());
            }
        }
        return out;
    }

    static String first(Map<String, List<String>> meta, String... keys) {
        for (String k : keys) {
            List<String> v = meta.get(k);
            if (v != null) {
                for (String s : v) {
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    static String title(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? unescape(m.group(1)).strip() : null;
    }

    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default ->
                    new String(Character.toChars(
                            e.startsWith("#x")
                                    ? Integer.parseInt(e.substring(2), 16)
                                    : Integer.parseInt(e.substring(1))));
            };
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** ISO-8601 durations as used in itemprop="duration": PT1H2M3S → 3723. */
    static Integer isoDuration(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = Pattern.compile("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$")
                .matcher(s.strip().toUpperCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        double secs = 0;
        if (m.group(1) != null) secs += Integer.parseInt(m.group(1)) * 86400;
        if (m.group(2) != null) secs += Integer.parseInt(m.group(2)) * 3600;
        if (m.group(3) != null) secs += Integer.parseInt(m.group(3)) * 60;
        if (m.group(4) != null) secs += Double.parseDouble(m.group(4));
        return secs > 0 ? (int) Math.round(secs) : null;
    }
}
