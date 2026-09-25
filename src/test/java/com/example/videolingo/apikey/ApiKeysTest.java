package com.example.videolingo.apikey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeysTest {

    @Test
    void generatesDistinctWellFormedKeys() {
        String a = ApiKeys.generate();
        String b = ApiKeys.generate();
        assertNotEquals(a, b);
        assertTrue(ApiKeys.looksLikeKey(a));
        assertEquals("vl_", a.substring(0, 3));
        assertEquals(11, ApiKeys.visiblePrefix(a).length());
    }

    @Test
    void tellsKeysFromJwts() {
        assertFalse(ApiKeys.looksLikeKey("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.sig"));
        assertFalse(ApiKeys.looksLikeKey("vl_short"));
        assertFalse(ApiKeys.looksLikeKey(null));
    }

    @Test
    void hashesStably() {
        assertEquals(ApiKeys.hash("vl_x"), ApiKeys.hash("vl_x"));
        assertEquals(64, ApiKeys.hash("vl_x").length());
        assertNotEquals(ApiKeys.hash("vl_x"), ApiKeys.hash("vl_y"));
    }
}
