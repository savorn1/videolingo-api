package com.example.videolingo.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GlossaryPromptTest {

    private static GlossaryEntry term(String source, String target) {
        return new GlossaryEntry(source, target, false, false, null);
    }

    @Test
    void matchesWholeWordsInSpacedScripts() {
        assertTrue(GlossaryPrompt.contains("Open the Dashboard now", "dashboard", false));
        assertFalse(GlossaryPrompt.contains("Let's start", "art", false));
        assertTrue(GlossaryPrompt.contains("art, then more", "art", false));
    }

    @Test
    void respectsCaseSensitivity() {
        assertFalse(GlossaryPrompt.contains("apple pie", "Apple", true));
        assertTrue(GlossaryPrompt.contains("Apple pie", "Apple", true));
    }

    @Test
    void matchesInsideUnspacedScripts() {
        // Khmer and Japanese don't separate words with spaces.
        assertTrue(GlossaryPrompt.contains("ខ្ញុំចូលចិត្តផ្ទាំងគ្រប់គ្រង", "ផ្ទាំងគ្រប់គ្រង", false));
        assertTrue(GlossaryPrompt.contains("東京タワーに行く", "東京", false));
    }

    @Test
    void keepsOnlyTermsTheBatchMentionsOncePerSource() {
        List<GlossaryEntry> terms = List.of(term("dashboard", "A"), term("Dashboard", "B"), term("invoice", "C"));
        List<GlossaryEntry> relevant = GlossaryPrompt.relevant(terms, List.of("Open the dashboard", "then log out"));
        assertEquals(1, relevant.size());
        assertEquals("A", relevant.get(0).target());
    }

    @Test
    void writesInstructionsForBothKindsOfTerm() {
        String section = GlossaryPrompt.section(List.of(
                term("dashboard", "ផ្ទាំងគ្រប់គ្រង"),
                new GlossaryEntry("VideoLingo", "VideoLingo", true, false, "brand name")));
        assertTrue(section.contains("\"dashboard\" → \"ផ្ទាំងគ្រប់គ្រង\""));
        assertTrue(section.contains("Keep \"VideoLingo\" as it is"));
        assertTrue(section.contains("(brand name)"));
        assertEquals("", GlossaryPrompt.section(List.of()));
    }
}
