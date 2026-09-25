package com.example.videolingo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A voice-over audio track for a video in one language, made by a DUB
// processing job from that language's transcript. Played in place of the
// video's own sound. At most one per (video, language); dubbing again
// replaces it.
@Entity
@Table(name = "video_dubs",
        uniqueConstraints = @UniqueConstraint(name = "uk_video_dubs_video_language", columnNames = {"video_id", "language"}),
        indexes = @Index(name = "idx_video_dubs_video_id", columnList = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoDub {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(nullable = false, length = 10)
    private String language;

    // Text-to-speech voice id, e.g. km-KH-SreymomNeural.
    @Column(nullable = false, length = 80)
    private String voice;

    @Column(name = "transcript_id")
    private Long transcriptId;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "audio_url", nullable = false, length = 1000)
    private String audioUrl;

    @Column(name = "mime_type", length = 50)
    private String mimeType;

    @Builder.Default
    @Column(name = "duration_ms", nullable = false)
    private long durationMs = 0;

    @Builder.Default
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes = 0;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
