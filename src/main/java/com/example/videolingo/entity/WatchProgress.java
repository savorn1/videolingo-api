package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// Where one user is in one video — what "Continue watching", resume and
// collection progress read. One row per (user, video), updated by the
// player's heartbeats (ProgressService). Individual viewing sessions are
// kept separately in VideoView, for statistics.
@Entity
@Table(name = "watch_progress",
        uniqueConstraints = @UniqueConstraint(name = "uk_watch_progress_user_video", columnNames = {"user_id", "video_id"}),
        indexes = @Index(name = "idx_watch_progress_user_recent", columnList = "user_id, last_watched_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    // Where playback was last reported.
    @Builder.Default
    @Column(name = "position_seconds", nullable = false)
    private int positionSeconds = 0;

    // As the player measured it (the video's own duration may be unknown).
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    // Real time spent playing, over every session.
    @Builder.Default
    @Column(name = "watched_seconds", nullable = false)
    private long watchedSeconds = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean completed = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "last_watched_at", nullable = false)
    private LocalDateTime lastWatchedAt;
}
