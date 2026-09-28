package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperLanguageTest {

    @Test
    void sendsTheHintOnlyForLanguagesTheApiAccepts() {
        assertEquals("en", WhisperClient.languageHint("en"));
        assertEquals("zh", WhisperClient.languageHint("zh-TW"));
        assertEquals("th", WhisperClient.languageHint("TH"));
        // Khmer is rejected with HTTP 400 when sent as a hint.
        assertNull(WhisperClient.languageHint("km"));
        assertNull(WhisperClient.languageHint(null));
    }

    @Test
    void autoDetectsUnsupportedLanguagesWithAPrimingPrompt() {
        assertTrue(WhisperClient.needsAutoDetect("km"));
        assertFalse(WhisperClient.needsAutoDetect("en"));
        assertFalse(WhisperClient.needsAutoDetect(null));
        assertNotNull(WhisperClient.primingPrompt("km"));
        assertNull(WhisperClient.primingPrompt("en"));
    }
}
