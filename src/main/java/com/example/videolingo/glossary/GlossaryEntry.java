package com.example.videolingo.glossary;

// One glossary rule as the Translator and the subtitle checker see it,
// detached from its entity.
public record GlossaryEntry(String source, String target, boolean doNotTranslate, boolean caseSensitive, String note) {
}
