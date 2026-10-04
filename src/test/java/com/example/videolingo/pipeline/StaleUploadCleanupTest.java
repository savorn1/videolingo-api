package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleUploadCleanupTest {

    @Test
    void deletesOnlyUploadsOlderThanTheCutoff() {
        S3Client s3 = mock(S3Client.class);
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(inv -> new ListObjectsV2Iterable(s3, inv.getArgument(0)));
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().isTruncated(false).contents(
                S3Object.builder().key("audio-uploads/old.mp3").lastModified(now.minusSeconds(5 * 86400)).build(),
                S3Object.builder().key("audio-uploads/new.mp3").lastModified(now.minusSeconds(3600)).build()).build());

        PipelineSteps steps = new PipelineSteps(null, null, null, null, null, null, null, null, null, null, null, null, null, null, s3, null, null);
        ReflectionTestUtils.setField(steps, "bucket", "videolingo");

        assertEquals(1, steps.deleteStale("audio-uploads/", now.minusSeconds(3 * 86400)));
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("videolingo").key("audio-uploads/old.mp3").build());
        verify(s3, never()).deleteObject(DeleteObjectRequest.builder().bucket("videolingo").key("audio-uploads/new.mp3").build());
    }

    @Test
    void doesNothingWithoutStorage() {
        PipelineSteps steps = new PipelineSteps(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(0, steps.deleteStale("audio-uploads/", Instant.now()));
    }
}
