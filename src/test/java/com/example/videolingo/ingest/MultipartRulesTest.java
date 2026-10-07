package com.example.videolingo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MultipartRulesTest {

    private static final long MB = 1024L * 1024;

    @Test
    void partsAre16MbUnlessTheFileWouldNeedMoreThan10000() {
        assertEquals(16 * MB, MultipartRules.partSize(100 * MB));
        assertEquals(16 * MB, MultipartRules.partSize(2048 * MB));
        long huge = 500_000 * MB;
        long size = MultipartRules.partSize(huge);
        assertTrue(MultipartRules.partCount(huge, size) <= 10_000);
    }

    @Test
    void partsCoverTheFileExactly() {
        long size = 100 * MB + 123;
        long part = MultipartRules.partSize(size);
        int count = MultipartRules.partCount(size, part);
        assertEquals(7, count);
        long sum = 0;
        for (int n = 1; n <= count; n++) {
            sum += MultipartRules.partLength(size, part, n);
        }
        assertEquals(size, sum);
        assertEquals(4 * MB + 123, MultipartRules.partLength(size, part, 7));
        assertEquals(0, MultipartRules.partLength(size, part, 8));
        assertEquals(0, MultipartRules.partLength(size, part, 0));
    }

    @Test
    void onlyFreshUploadKeysAreAccepted() {
        assertTrue(MultipartRules.isUploadKey("videos/0b0c7a8e-1f2d-4c3b-9a8e-123456789abc.mp4"));
        assertTrue(MultipartRules.isUploadKey("audio-uploads/0b0c7a8e-1f2d-4c3b-9a8e-123456789abc.m4a"));
        assertFalse(MultipartRules.isUploadKey("videos/../secrets.mp4"));
        assertFalse(MultipartRules.isUploadKey("videos/my-lesson.mp4"));
        assertFalse(MultipartRules.isUploadKey("other/0b0c7a8e-1f2d-4c3b-9a8e-123456789abc.mp4"));
        assertFalse(MultipartRules.isUploadKey(null));
    }
}
