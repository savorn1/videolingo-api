package com.example.videolingo.notification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// {{variable}} substitution for notification subjects and bodies. Plain text
// only — nothing is HTML-escaped or interpreted, and the UI renders it as text.
public final class TemplateRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    /** Filled per recipient by the server; a sender can't override them. */
    public static final List<String> BUILT_INS = List.of("username", "email", "role", "appName", "appUrl", "date");

    private TemplateRenderer() {
    }

    /** Variable names used across the given texts, in order of first appearance. */
    public static Set<String> variables(String... texts) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = VARIABLE.matcher(text);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** The variables a sender has to supply (everything that isn't built in). */
    public static Set<String> customVariables(String... texts) {
        Set<String> names = variables(texts);
        names.removeAll(BUILT_INS);
        return names;
    }

    /** Custom variables with no (or a blank) value in {@code supplied}. */
    public static Set<String> missing(Set<String> custom, Map<String, String> supplied) {
        Set<String> missing = new LinkedHashSet<>();
        for (String name : custom) {
            String value = supplied == null ? null : supplied.get(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** Replaces every known variable; unknown ones are left as written. */
    public static String render(String text, Map<String, String> values) {
        Matcher m = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
