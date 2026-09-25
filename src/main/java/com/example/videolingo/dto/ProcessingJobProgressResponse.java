package com.example.videolingo.dto;

import com.example.videolingo.entity.ProcessingJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// Deliberately small — polled every few seconds while a job is active.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingJobProgressResponse {

    private Long id;
    private ProcessingJobStatus status;
    private int progress;
    private String currentStep;
    private String errorMessage;
    private Long durationSeconds;
    private LocalDateTime updatedAt;
    // Highest log id so far — a client can tell whether to fetch new lines.
    private Long lastLogId;
}
