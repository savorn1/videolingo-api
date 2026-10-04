package com.example.videolingo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuditPolicyTest {

    @Test
    void logsChangesButNotReads() {
        assertTrue(AuditPolicy.shouldLog("DELETE", "/api/admin/videos/4"));
        assertTrue(AuditPolicy.shouldLog("post", "/api/auth/login"));
        assertTrue(AuditPolicy.shouldLog("PUT", "/api/users/me/password"));
        assertFalse(AuditPolicy.shouldLog("GET", "/api/admin/videos"));
        assertFalse(AuditPolicy.shouldLog("POST", "/api/auth/refresh"));
        assertFalse(AuditPolicy.shouldLog("POST", "/api/users/me/notifications/3/read"));
    }

    @Test
    void namesTheModule() {
        assertEquals("subtitles", AuditPolicy.moduleOf("/api/admin/subtitles/9/approve"));
        assertEquals("auth", AuditPolicy.moduleOf("/api/auth/login"));
        assertEquals("profile", AuditPolicy.moduleOf("/api/users/me"));
    }

    @Test
    void findsTheRecordId() {
        assertEquals(12L, AuditPolicy.entityIdOf("/api/admin/videos/12/tags/3"));
        assertNull(AuditPolicy.entityIdOf("/api/admin/videos"));
        assertNull(AuditPolicy.entityIdOf("/api/auth/login"));
    }
}
