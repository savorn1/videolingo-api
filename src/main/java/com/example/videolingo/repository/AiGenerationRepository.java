package com.example.videolingo.repository;

import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiGeneration;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiGenerationRepository extends JpaRepository<AiGeneration, Long> {

    // Newest generation per (type, output language) for a video.
    @Query(
            """
            select g from AiGeneration g
            where g.videoId = :videoId and g.createdAt = (
                select max(g2.createdAt) from AiGeneration g2
                where g2.videoId = g.videoId and g2.type = g.type and g2.outputLanguage = g.outputLanguage)
            order by g.type, g.outputLanguage
            """)
    List<AiGeneration> latestForVideo(@Param("videoId") Long videoId);

    Page<AiGeneration> findByVideoIdAndTypeOrderByCreatedAtDesc(Long videoId, AiFeature type, Pageable pageable);

    long countByVideoId(Long videoId);
}
