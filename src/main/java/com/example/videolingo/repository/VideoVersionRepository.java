package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoVersionRepository extends JpaRepository<VideoVersion, Long> {

    List<VideoVersion> findByVideoIdOrderByIdDesc(Long videoId);
}
