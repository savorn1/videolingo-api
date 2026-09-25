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

// A learner's flashcard: a word saved from a subtitle, or an AI key point.
// Scheduled for review by spaced repetition (learn/Srs): the better it's
// remembered, the longer until it's shown again.
@Entity
@Table(name = "study_cards", indexes = {
        @Index(name = "idx_study_cards_user_due", columnList = "user_id, due_at"),
        @Index(name = "idx_study_cards_user_front", columnList = "user_id, front_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudyCard {

    public enum Source { WORD, KEY_POINT, MANUAL }

    /** A new card's ease (learn/Srs adjusts it with every review). */
    public static final double START_EASE = 2.5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // The prompt side: a word, or a key point.
    @Column(nullable = false, length = 300)
    private String front;

    // Lower-cased front, for "already saved?" checks.
    @Column(name = "front_key", nullable = false, length = 300)
    private String frontKey;

    // The answer side: the translation / explanation.
    @Column(nullable = false, length = 1000)
    private String back;

    // Language of the front.
    @Column(length = 10)
    private String language;

    // Where it came from: the cue it was saved from, and the moment in the video.
    @Column(length = 500)
    private String context;

    @Column(name = "video_id")
    private Long videoId;

    @Column(name = "at_ms")
    private Long atMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Source source = Source.WORD;

    // ── Spaced repetition state (see Srs) ─────────────────────────────────
    @Builder.Default
    @Column(nullable = false)
    private double ease = START_EASE;

    @Builder.Default
    @Column(name = "interval_days", nullable = false)
    private int intervalDays = 0;

    @Builder.Default
    @Column(nullable = false)
    private int repetitions = 0;

    @Builder.Default
    @Column(nullable = false)
    private int lapses = 0;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (dueAt == null) {
            dueAt = now;
        }
    }
}
