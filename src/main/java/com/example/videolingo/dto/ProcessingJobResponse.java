package com.example.videolingo.dto;

import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingJobResponse {

    private Long id;
    private Long videoId;
    // Null if the video row no longer exists.
    private String videoTitle;
    private ProcessingJobType type;
    private ProcessingJobStatus status;
    private int progress;
    private String currentStep;
    private String parameters;
    private int attempts;
    private int maxAttempts;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    // Seconds from start to finish (or to now while running); null if never started.
    private Long durationSeconds;
    private long logCount;
    // What the admin may do right now — computed here so the UI never has to
    // duplicate the state machine.
    private boolean canRetry;
    private boolean canCancel;
    // How many queued jobs (of the types the worker handles) are ahead of this one; null unless QUEUED.
    private Integer queuePosition;
    private boolean canDelete;
}
