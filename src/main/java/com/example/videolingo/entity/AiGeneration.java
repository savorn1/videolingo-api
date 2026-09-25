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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A generated summary / chapter list / key points / question set / quiz for a
// video. Every run is kept (history); the newest per (video, type, language) is
// the current one.
@Entity
@Table(name = "ai_generations", indexes = @Index(name = "idx_ai_generations_video", columnList = "video_id, type, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "transcript_id", nullable = false)
    private Long transcriptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiFeature type;

    @Column(name = "output_language", nullable = false, length = 10)
    private String outputLanguage;

    @Column(nullable = false, length = 60)
    private String model;

    // The validated structured output, as JSON.
    @Column(name = "content_json", nullable = false, columnDefinition = "text")
    private String contentJson;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    // Newline-separated notes from OutputValidator (dropped/repaired items).
    @Column(columnDefinition = "text")
    private String warnings;

    @Column(name = "usage_id")
    private Long usageId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
