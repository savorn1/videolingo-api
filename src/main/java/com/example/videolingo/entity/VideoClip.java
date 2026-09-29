package com.example.videolingo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A clip produced by an EDIT job — a trim/crop of the whole video, one
// segment of a split, the video with re-rendered sound (AUDIO), or its sound
// as a standalone file (EXTRACT) — kept for review until an admin promotes it
// (TRIM/AUDIO: replaces the video's file; SPLIT: becomes a new Video),
// downloads it (EXTRACT) or discards it.
// Promoting deletes this row (ownership of the file moves to the Video row),
// so there's never a clip pointing at a file something else now owns.
@Entity
@Table(name = "video_clips", indexes = @Index(name = "idx_video_clips_video_id", columnList = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoClip {

    public enum Operation {
        TRIM, SPLIT, AUDIO, EXTRACT, OVERLAY;

        /** Promoting it swaps the video's own file. */
        public boolean replacesVideo() {
            return this == TRIM || this == AUDIO || this == OVERLAY;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "job_id")
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Operation operation;

    // Null for TRIM; 0..n-1 for SPLIT (this segment's place among the job's outputs).
    @Column(name = "segment_index")
    private Integer segmentIndex;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    // Null = to the end of the source.
    @Column(name = "end_ms")
    private Long endMs;

    @Column(name = "crop_x")
    private Integer cropX;
    @Column(name = "crop_y")
    private Integer cropY;
    @Column(name = "crop_w")
    private Integer cropW;
    @Column(name = "crop_h")
    private Integer cropH;

    @Column(name = "scale_w")
    private Integer scaleW;
    @Column(name = "scale_h")
    private Integer scaleH;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    private Integer width;

    private Integer height;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(nullable = false, length = 1000)
    private String url;

    // What an AUDIO edit changed ("Volume 150%, fade in 0.5 s…"); EXTRACT: the format.
    @Column(length = 300)
    private String summary;

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

    public boolean hasCrop() {
        return cropW != null && cropH != null;
    }
}
