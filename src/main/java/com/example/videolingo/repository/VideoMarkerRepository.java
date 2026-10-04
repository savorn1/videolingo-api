package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoMarker;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoMarkerRepository extends JpaRepository<VideoMarker, Long> {

    List<VideoMarker> findByVideoIdOrderByAtMsAsc(Long videoId);
}
