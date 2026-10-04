package com.example.videolingo.dto;

import com.example.videolingo.entity.ReviewStatus;
import com.example.videolingo.entity.SubtitleKind;
import com.example.videolingo.entity.SubtitleSource;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleResponse {

    private Long id;
    private Long videoId;
    private String videoTitle;
    private String videoUrl;
    private String language;
    private String label;
    private SubtitleKind kind;
    private SubtitleSource source;
    private Long transcriptId;
    private String transcriptLanguage;
    private boolean published;
    // Boxed so Jackson names it "isDefault" (see LanguageResponse).
    private Boolean isDefault;
    private SubtitleRulesDto rules;
    private int cueCount;
    private int issueCount;
    private long durationMs;
    private String originalFilename;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    private ReviewStatus reviewStatus;
    private String reviewRequestedBy;
    private LocalDateTime reviewRequestedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    // Detail only: unresolved review comments.
    private Long openComments;
    // Detail only.
    private List<SubtitleCueDto> cues;
    private List<SubtitleIssueDto> issues;
    // Upload only: cues the parser skipped, and why.
    private List<String> warnings;
}
