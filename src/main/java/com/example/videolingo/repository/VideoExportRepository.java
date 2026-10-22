package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoExport;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoExportRepository extends JpaRepository<VideoExport, Long> {

    /** Per video: how many of these files it has and their bytes ([videoId, count, bytes]), for the storage report. */
    @org.springframework.data.jpa.repository.Query(
            "select x.videoId, count(x), coalesce(sum(x.sizeBytes), 0) from VideoExport x group by x.videoId")
    List<Object[]> sizeByVideo();

    List<VideoExport> findByVideoIdAndExpiresAtAfterOrderByIdDesc(Long videoId, LocalDateTime now);

    List<VideoExport> findByExpiresAtBefore(LocalDateTime now);
}
