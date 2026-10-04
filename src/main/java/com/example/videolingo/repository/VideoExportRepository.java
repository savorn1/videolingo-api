package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoExport;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoExportRepository extends JpaRepository<VideoExport, Long> {

    List<VideoExport> findByVideoIdAndExpiresAtAfterOrderByIdDesc(Long videoId, LocalDateTime now);

    List<VideoExport> findByExpiresAtBefore(LocalDateTime now);
}
