package com.example.videolingo.pipeline;

import com.example.videolingo.entity.ProcessingJobType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
