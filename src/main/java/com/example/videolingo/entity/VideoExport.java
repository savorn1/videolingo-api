package com.example.videolingo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// An MP4 prepared by a DOWNLOAD job for an admin to save — the video (from
// its link or our storage), optionally with a voice-over as its sound. Kept
// for a limited time, then deleted with its file (see VideoDownloadService).
@Entity
@Table(name = "video_exports", indexes = @Index(name = "idx_video_exports_video_id", columnList = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "job_id")
    private Long jobId;

    // Voice-over language used as the sound; null = the original sound.
    @Column(name = "audio_language", length = 10)
    private String audioLanguage;

    // The subtitle track drawn into the picture, if any (its label at the time).
    @Column(name = "subtitle_label", length = 100)
    private String subtitleLabel;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(nullable = false, length = 1000)
    private String url;

    @Builder.Default
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
