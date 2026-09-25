package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoDub;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VideoDubRepository extends JpaRepository<VideoDub, Long> {

    List<VideoDub> findByVideoIdOrderByLanguageAsc(Long videoId);

    Optional<VideoDub> findByVideoIdAndLanguage(Long videoId, String language);
}
