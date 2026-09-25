package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// A review comment on a subtitle track. Anchored to a point in time
// (`atMs`) rather than a cue id, because cues are replaced wholesale on every
// save — the editor shows it on whichever cue covers that moment. Null atMs
// = about the whole track.
@Entity
@Table(name = "subtitle_comments", indexes = @Index(name = "idx_subtitle_comments_subtitle", columnList = "subtitle_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubtitleComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subtitle_id", nullable = false)
    private Long subtitleId;

    @Column(name = "at_ms")
    private Long atMs;

    // The cue's text when the comment was written, so it still makes sense
    // after that cue is edited away.
    @Column(name = "cue_text", length = 500)
    private String cueText;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false, length = 100)
    private String author;

    @Builder.Default
    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
