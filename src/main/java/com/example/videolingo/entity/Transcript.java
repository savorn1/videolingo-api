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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// The timed text of a video in one language — the spoken-language transcript,
// or a translation of it. At most one per (video, language). The text itself
// lives in TranscriptSegment rows; the counts here are denormalised from them
// on every save so the list page never has to aggregate segments.
@Entity
@Table(name = "transcripts",
        uniqueConstraints = @UniqueConstraint(name = "uk_transcripts_video_language", columnNames = {"video_id", "language"}),
        indexes = @Index(name = "idx_transcripts_video_id", columnList = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transcript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    // ISO 639-1, same vocabulary as Video.language.
    @Column(nullable = false, length = 10)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TranscriptSource source = TranscriptSource.MANUAL;

    @Builder.Default
    @Column(name = "segment_count", nullable = false)
    private int segmentCount = 0;

    @Builder.Default
    @Column(name = "word_count", nullable = false)
    private int wordCount = 0;

    // End of the last segment.
    @Builder.Default
    @Column(name = "duration_ms", nullable = false)
    private long durationMs = 0;

    // The most recent regeneration job, if any — lets the UI follow it and
    // stops a second regeneration being queued while one is still active.
    @Column(name = "last_job_id")
    private Long lastJobId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Clients send back the version they loaded; a mismatch means someone (or
    // a finished regeneration job) changed the transcript in the meantime.
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
