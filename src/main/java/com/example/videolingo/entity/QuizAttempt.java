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

// One go at an AI-generated quiz (an AiGeneration of type QUIZ) by a learner,
// graded on the server so the answers never have to reach the browser first.
@Entity
@Table(name = "quiz_attempts", indexes = {
        @Index(name = "idx_quiz_attempts_user", columnList = "user_id, video_id"),
        @Index(name = "idx_quiz_attempts_generation", columnList = "generation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "generation_id", nullable = false)
    private Long generationId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private int total;

    // JSON array of the chosen option per question (null = skipped).
    @Column(nullable = false, columnDefinition = "text")
    private String answers;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
