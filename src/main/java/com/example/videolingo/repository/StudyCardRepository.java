package com.example.videolingo.repository;

import com.example.videolingo.entity.StudyCard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudyCardRepository extends JpaRepository<StudyCard, Long>, JpaSpecificationExecutor<StudyCard> {

    Optional<StudyCard> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndFrontKeyAndLanguage(Long userId, String frontKey, String language);

    @Query("select c from StudyCard c where c.userId = :userId and c.dueAt <= :now order by c.dueAt asc")
    List<StudyCard> due(@Param("userId") Long userId, @Param("now") LocalDateTime now, Pageable pageable);

    long countByUserIdAndDueAtLessThanEqual(Long userId, LocalDateTime now);

    long countByUserId(Long userId);
}
