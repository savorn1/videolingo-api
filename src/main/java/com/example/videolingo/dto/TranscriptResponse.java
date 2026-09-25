package com.example.videolingo.dto;

import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.TranscriptSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptResponse {

    private Long id;
    private Long videoId;
    private String videoTitle;
    // The video's own spoken language — a transcript in another language is a translation.
    private String videoLanguage;
    private Integer videoDurationSeconds;
    private String videoUrl;
    private String language;
    private TranscriptSource source;
    private int segmentCount;
    private int wordCount;
    private long durationMs;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    // Most recent regeneration job; null if never regenerated.
    private Long lastJobId;
    private ProcessingJobStatus lastJobStatus;
    private Integer lastJobProgress;
    // Only on the single-transcript response.
    private List<TranscriptSegmentDto> segments;
}
