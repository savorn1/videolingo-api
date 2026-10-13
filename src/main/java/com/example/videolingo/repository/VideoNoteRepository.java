package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoNote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoNoteRepository extends JpaRepository<VideoNote, Long> {

    List<VideoNote> findByVideoIdOrderByCreatedAtAsc(Long videoId);

    Optional<VideoNote> findByIdAndVideoId(Long id, Long videoId);

    @Modifying
    @Query("delete from VideoNote n where n.videoId = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);
}
