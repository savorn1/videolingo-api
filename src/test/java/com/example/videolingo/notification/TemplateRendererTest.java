package com.example.videolingo.notification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateRendererTest {

    @Test
    void findsVariablesInOrderAcrossTexts() {
        assertEquals(List.of("username", "course", "date"),
                List.copyOf(TemplateRenderer.variables("Hi {{ username }}", "New: {{course}} on {{date}} — {{course}}")));
    }

    @Test
    void customVariablesExcludeBuiltIns() {
        assertEquals(Set.of("course"), TemplateRenderer.customVariables("Hi {{username}}, {{course}} is live at {{appUrl}}"));
    }

    @Test
    void blankValuesCountAsMissing() {
        assertEquals(Set.of("b", "c"), TemplateRenderer.missing(Set.of("a", "b", "c"), Map.of("a", "x", "b", "  ")));
    }

    @Test
    void rendersKnownAndKeepsUnknownAndSpecialCharacters() {
        assertEquals("Price $5 \\o/ for ann — {{other}}",
                TemplateRenderer.render("Price {{price}} for {{ username }} — {{other}}", Map.of("price", "$5 \\o/", "username", "ann")));
    }

    @Test
    void ignoresMalformedPlaceholders() {
        assertEquals(Set.of(), TemplateRenderer.variables("{{ 1abc }} {{}} {single}"));
    }
}
