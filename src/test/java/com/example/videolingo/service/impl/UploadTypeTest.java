package com.example.videolingo.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadTypeTest {

    @Test
    void emptyListAllowsEverything() {
        assertTrue(S3FileStorageService.typeAllowed("application/zip", List.of()));
        assertTrue(S3FileStorageService.typeAllowed(null, List.of()));
    }

    @Test
    void matchesExactTypesAndFamilies() {
        List<String> allowed = List.of("image/*", "application/pdf");
        assertTrue(S3FileStorageService.typeAllowed("image/png", allowed));
        assertTrue(S3FileStorageService.typeAllowed("IMAGE/JPEG; charset=binary", allowed));
        assertTrue(S3FileStorageService.typeAllowed("application/pdf", allowed));
        assertFalse(S3FileStorageService.typeAllowed("application/pdfx", allowed));
        assertFalse(S3FileStorageService.typeAllowed("video/mp4", allowed));
        assertFalse(S3FileStorageService.typeAllowed(null, allowed));
    }
}
