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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A subtitle track for a video: display-ready cues (short, line-wrapped,
// paced for reading) — as opposed to a Transcript, which is the raw timed
// text. Usually generated from a transcript; can also be uploaded or typed.
// A video may have several tracks, even in one language ("English",
// "English (simplified)"); at most one per video is the default.
@Entity
@Table(name = "subtitles", indexes = @Index(name = "idx_subtitles_video_id", columnList = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subtitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(nullable = false, length = 10)
    private String language;

    // Shown in the player's track menu. Unique per video.
    @Column(nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubtitleKind kind = SubtitleKind.SUBTITLES;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubtitleSource source = SubtitleSource.MANUAL;

    // The transcript this track was generated from; regenerating reuses it.
    @Column(name = "transcript_id")
    private Long transcriptId;

    // Only published tracks are offered to learners.
    @Builder.Default
    @Column(nullable = false)
    private boolean published = false;

    // Pre-selected in the player. At most one per video, and only if published.
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    // Readability rules this track is generated with and checked against.
    @Column(name = "max_chars_per_line", nullable = false)
    private int maxCharsPerLine;

    @Column(name = "max_lines", nullable = false)
    private int maxLines;

    @Column(name = "min_duration_ms", nullable = false)
    private long minDurationMs;

    @Column(name = "max_duration_ms", nullable = false)
    private long maxDurationMs;

    @Column(name = "max_cps", nullable = false)
    private double maxCps;

    @Builder.Default
    @Column(name = "cue_count", nullable = false)
    private int cueCount = 0;

    // Readability warnings at last save — lets the list flag tracks needing work.
    @Builder.Default
    @Column(name = "issue_count", nullable = false)
    private int issueCount = 0;

    @Builder.Default
    @Column(name = "duration_ms", nullable = false)
    private long durationMs = 0;

    // Review workflow (SubtitleReviewService). Nullable so ddl-auto can add
    // the column to existing rows — read it through reviewStatus(), which
    // treats null as DRAFT.
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 20)
    private ReviewStatus reviewStatus;

    // Who sent it for review, and when (the one notified of the decision).
    @Column(name = "review_requested_by", length = 100)
    private String reviewRequestedBy;

    @Column(name = "review_requested_at")
    private LocalDateTime reviewRequestedAt;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // The submitter's note while IN_REVIEW; the reviewer's once decided.
    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public ReviewStatus reviewStatus() {
        return reviewStatus == null ? ReviewStatus.DRAFT : reviewStatus;
    }

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
