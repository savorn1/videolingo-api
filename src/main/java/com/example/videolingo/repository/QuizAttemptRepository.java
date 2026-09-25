package com.example.videolingo.repository;

import com.example.videolingo.entity.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    List<QuizAttempt> findByUserIdAndVideoIdOrderByCreatedAtDesc(Long userId, Long videoId);

    List<QuizAttempt> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndGenerationId(Long userId, Long generationId);
}
