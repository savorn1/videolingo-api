package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoClip;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoClipRepository extends JpaRepository<VideoClip, Long> {

    /** Per video: how many of these files it has and their bytes ([videoId, count, bytes]), for the storage report. */
    @org.springframework.data.jpa.repository.Query(
            "select x.videoId, count(x), coalesce(sum(x.sizeBytes), 0) from VideoClip x group by x.videoId")
    List<Object[]> sizeByVideo();

    List<VideoClip> findByVideoIdAndExpiresAtAfterOrderByIdDesc(Long videoId, LocalDateTime now);

    List<VideoClip> findByExpiresAtBefore(LocalDateTime now);

    /** The newest results still kept, across every video (the "Edit results" page). */
    List<VideoClip> findTop200ByExpiresAtAfterOrderByIdDesc(LocalDateTime now);
}
