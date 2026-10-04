package com.example.videolingo.pipeline;

import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;

// Published by JobWorker when a job ends as SUCCEEDED or FAILED (not when
// cancelled — whoever cancelled it already knows). Webhooks listen for it.
public record JobFinishedEvent(
        Long jobId, Long videoId, ProcessingJobType type, ProcessingJobStatus status, String errorMessage) {}
