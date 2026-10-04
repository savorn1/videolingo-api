package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One viewing session of a video by a learner — the raw data behind video
// statistics (and, later, a user's watch history). Written by the learner
// app; the admin side only reads it.
@Entity
@Table(
        name = "video_views",
        indexes = {
            @Index(name = "idx_video_views_video_id", columnList = "video_id, viewed_at"),
            @Index(name = "idx_video_views_user_id", columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    // Null for anonymous views.
    @Column(name = "user_id")
    private Long userId;

    @Builder.Default
    @Column(name = "watched_seconds", nullable = false)
    private int watchedSeconds = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean completed = false;

    // The player session that wrote it (ProgressService), so one sitting is
    // one view however many heartbeats it sends. Null for older rows.
    @Column(name = "session_id", length = 40)
    private String sessionId;

    @Column(name = "viewed_at", nullable = false)
    private LocalDateTime viewedAt;

    @PrePersist
    void onCreate() {
        if (viewedAt == null) {
            viewedAt = LocalDateTime.now();
        }
    }
}
