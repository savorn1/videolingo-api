package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoExport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface VideoExportRepository extends JpaRepository<VideoExport, Long> {

    List<VideoExport> findByVideoIdAndExpiresAtAfterOrderByIdDesc(Long videoId, LocalDateTime now);

    List<VideoExport> findByExpiresAtBefore(LocalDateTime now);
}
