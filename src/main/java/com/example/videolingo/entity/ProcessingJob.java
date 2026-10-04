package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One unit of background work on a video (transcode, transcribe, …).
//
// Contract for the worker that executes jobs (not part of this service yet):
//   1. Claim a QUEUED job: set RUNNING, startedAt, attempts + 1.
//   2. While working, update progress (0–100) / currentStep and append
//      ProcessingJobLog rows. Before each step, re-read the job — if an admin
//      cancelled it (status CANCELLED), stop without overwriting the status.
//   3. Finish with SUCCEEDED (progress 100) or FAILED (+ errorMessage),
//      setting finishedAt.
// @Version guards the admin-vs-worker race: a stale save from either side
// fails instead of silently undoing the other's change.
@Entity
@Table(
        name = "processing_jobs",
        indexes = {
            @Index(name = "idx_processing_jobs_video_id", columnList = "video_id"),
            @Index(name = "idx_processing_jobs_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProcessingJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProcessingJobStatus status = ProcessingJobStatus.QUEUED;

    // 0–100.
    @Builder.Default
    @Column(nullable = false)
    private int progress = 0;

    // Human-readable description of what the worker is doing right now.
    @Column(name = "current_step", length = 200)
    private String currentStep;

    // Job-type-specific input as JSON, e.g. {"targetLanguage":"en"}.
    @Column(columnDefinition = "text")
    private String parameters;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Builder.Default
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
