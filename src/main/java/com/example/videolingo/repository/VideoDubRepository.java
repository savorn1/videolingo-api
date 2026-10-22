package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoDub;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoDubRepository extends JpaRepository<VideoDub, Long> {

    /** Per video: how many of these files it has and their bytes ([videoId, count, bytes]), for the storage report. */
    @org.springframework.data.jpa.repository.Query(
            "select x.videoId, count(x), coalesce(sum(x.sizeBytes), 0) from VideoDub x group by x.videoId")
    List<Object[]> sizeByVideo();

    List<VideoDub> findByVideoIdOrderByLanguageAsc(Long videoId);

    Optional<VideoDub> findByVideoIdAndLanguage(Long videoId, String language);
}
