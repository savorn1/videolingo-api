package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoVersionRepository extends JpaRepository<VideoVersion, Long> {

    /** Per video: how many of these files it has and their bytes ([videoId, count, bytes]), for the storage report. */
    @org.springframework.data.jpa.repository.Query(
            "select x.videoId, count(x), coalesce(sum(x.fileSize), 0) from VideoVersion x group by x.videoId")
    List<Object[]> sizeByVideo();

    List<VideoVersion> findByVideoIdOrderByIdDesc(Long videoId);
}
