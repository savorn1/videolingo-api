package com.example.videolingo.repository;

import com.example.videolingo.entity.SubtitleCue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubtitleCueRepository extends JpaRepository<SubtitleCue, Long> {

    List<SubtitleCue> findBySubtitleIdOrderByPositionAsc(Long subtitleId);

    @Modifying
    @Query("delete from SubtitleCue c where c.subtitleId = :subtitleId")
    void deleteBySubtitleId(@Param("subtitleId") Long subtitleId);
}
