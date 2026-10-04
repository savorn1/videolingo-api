package com.example.videolingo.repository;

import com.example.videolingo.entity.Transcript;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptRepository extends JpaRepository<Transcript, Long>, JpaSpecificationExecutor<Transcript> {

    boolean existsByVideoIdAndLanguage(Long videoId, String language);

    Optional<Transcript> findByVideoIdAndLanguage(Long videoId, String language);

    List<Transcript> findByVideoId(Long videoId);

    boolean existsByVideoIdAndLanguageAndIdNot(Long videoId, String language, Long id);

    @Query(
            "select t.language as language, count(t) as count from Transcript t where lower(t.language) in :codes group by t.language")
    List<VideoRepository.LanguageUsage> countByLanguages(@Param("codes") Collection<String> codes);
}
