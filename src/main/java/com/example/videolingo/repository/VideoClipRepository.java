package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoClip;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoClipRepository extends JpaRepository<VideoClip, Long> {

    List<VideoClip> findByVideoIdAndExpiresAtAfterOrderByIdDesc(Long videoId, LocalDateTime now);

    List<VideoClip> findByExpiresAtBefore(LocalDateTime now);
}
