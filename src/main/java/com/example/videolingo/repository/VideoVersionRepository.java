package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoVersionRepository extends JpaRepository<VideoVersion, Long> {

    List<VideoVersion> findByVideoIdOrderByIdDesc(Long videoId);
}
