package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoMarker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoMarkerRepository extends JpaRepository<VideoMarker, Long> {

    List<VideoMarker> findByVideoIdOrderByAtMsAsc(Long videoId);
}
