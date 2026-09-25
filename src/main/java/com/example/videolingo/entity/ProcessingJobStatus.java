package com.example.videolingo.entity;

// Lifecycle of a ProcessingJob:
//   QUEUED -> RUNNING -> SUCCEEDED | FAILED
//   QUEUED | RUNNING -> CANCELLED   (admin cancel)
//   FAILED | CANCELLED -> QUEUED    (admin retry)
public enum ProcessingJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
