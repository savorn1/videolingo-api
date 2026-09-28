package com.example.videolingo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A named timestamp on a video's timeline (e.g. "intro ends"), shown as a
// flag on the ruler and used as a snap target when dragging the playhead.
@Entity
@Table(name = "video_markers", indexes = @Index(name = "idx_video_markers_video_id", columnList = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoMarker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "at_ms", nullable = false)
    private long atMs;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(length = 20)
    private String color;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
