package com.example.videolingo.dto;

import com.example.videolingo.entity.ProcessingJobStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    // How many queued jobs (of the types the worker handles) are ahead of this one; null unless QUEUED.
    private Integer queuePosition;
}
