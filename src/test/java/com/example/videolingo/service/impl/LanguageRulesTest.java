package com.example.videolingo.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.entity.Language;
import org.junit.jupiter.api.Test;

class LanguageRulesTest {

    @Test
    void codesAreStoredInCanonicalBcp47Casing() {
        assertEquals("en", LanguageServiceImpl.canonicalCode("EN"));
        assertEquals("pt-BR", LanguageServiceImpl.canonicalCode("PT-br"));
        assertEquals("zh-Hant", LanguageServiceImpl.canonicalCode("zh-HANT"));
        assertEquals("zh-Hant-TW", LanguageServiceImpl.canonicalCode("ZH-hant-tw"));
        assertEquals("es-419", LanguageServiceImpl.canonicalCode("es-419"));
        assertEquals("km", LanguageServiceImpl.canonicalCode("  km "));
    }

    @Test
    void usageIsDescribedInPlainWords() {
        assertEquals("1 video", LanguageServiceImpl.describeUsage(1, 0));
        assertEquals("3 transcripts", LanguageServiceImpl.describeUsage(0, 3));
        assertEquals("2 videos and 1 transcript", LanguageServiceImpl.describeUsage(2, 1));
    }

    @Test
    void deleteIsBlockedForTheDefaultAndForLanguagesInUse() {
        Language plain = Language.builder().code("fr").name("French").build();
        Language fallback =
                Language.builder().code("en").name("English").isDefault(true).build();

        assertNull(LanguageServiceImpl.deleteBlockedReason(plain, 0, 0));
        assertTrue(LanguageServiceImpl.deleteBlockedReason(fallback, 0, 0).contains("default"));
        assertTrue(LanguageServiceImpl.deleteBlockedReason(plain, 2, 1).contains("2 videos and 1 transcript"));
    }
}
