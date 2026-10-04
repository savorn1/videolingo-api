package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.entity.ProcessingJobType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobLaneTest {

    @Test
    void everyTypeTheWorkerHandlesIsInExactlyOneLane() {
        Set<ProcessingJobType> seen = EnumSet.noneOf(ProcessingJobType.class);
        for (JobLane lane : JobLane.values()) {
            for (ProcessingJobType type : lane.types()) {
                assertTrue(seen.add(type), type + " is in more than one lane");
            }
        }
        assertEquals(JobWorker.HANDLED, seen, "the lanes must cover exactly what the worker handles");
    }

    @Test
    void slowAiWorkIsKeptApartFromMediaWork() {
        assertEquals(JobLane.AI, JobLane.of(ProcessingJobType.TRANSCRIBE));
        assertEquals(JobLane.AI, JobLane.of(ProcessingJobType.TRANSLATE));
        assertEquals(JobLane.AI, JobLane.of(ProcessingJobType.DUB));
        assertEquals(JobLane.MEDIA, JobLane.of(ProcessingJobType.EDIT));
        assertEquals(JobLane.MEDIA, JobLane.of(ProcessingJobType.DOWNLOAD));
    }

    @Test
    void aTypeNobodyHandlesHasNoLane() {
        assertNull(JobLane.of(ProcessingJobType.TRANSCODE));
        assertNull(JobLane.of(ProcessingJobType.GENERATE_THUMBNAIL));
    }

    @Test
    void onlyTemporaryEditUploadsAreReportedAsExpired() {
        assertTrue(PipelineSteps.isEditUpload(AudioEditRules.UPLOAD_PREFIX + "a.mp3"));
        assertTrue(PipelineSteps.isEditUpload(OverlayRules.UPLOAD_PREFIX + "c.png"));
        org.junit.jupiter.api.Assertions.assertFalse(PipelineSteps.isEditUpload("videos/x.mp4"));
        org.junit.jupiter.api.Assertions.assertFalse(PipelineSteps.isEditUpload(null));
    }

    @Test
    void concurrencyDefaultsToOneAndIsCapped() {
        PipelineProperties none =
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(1, none.concurrency(JobLane.MEDIA));
        assertEquals(1, none.concurrency(JobLane.AI));
        PipelineProperties set =
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, 2, 99);
        assertEquals(2, set.concurrency(JobLane.MEDIA));
        assertEquals(PipelineProperties.MAX_CONCURRENCY, set.concurrency(JobLane.AI));
        PipelineProperties bad =
                new PipelineProperties(null, null, null, null, null, null, null, null, null, null, null, 0, -3);
        assertEquals(1, bad.concurrency(JobLane.MEDIA));
        assertEquals(1, bad.concurrency(JobLane.AI));
    }
}
