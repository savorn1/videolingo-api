package com.example.videolingo.repository;

import com.example.videolingo.entity.SubtitleComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubtitleCommentRepository extends JpaRepository<SubtitleComment, Long> {

    List<SubtitleComment> findBySubtitleIdOrderByCreatedAtAsc(Long subtitleId);

    Optional<SubtitleComment> findByIdAndSubtitleId(Long id, Long subtitleId);

    long countBySubtitleIdAndResolvedFalse(Long subtitleId);

    @Modifying
    @Query("delete from SubtitleComment c where c.subtitleId = :subtitleId")
    void deleteBySubtitleId(@Param("subtitleId") Long subtitleId);
}
