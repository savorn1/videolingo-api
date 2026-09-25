package com.example.videolingo.repository;

import com.example.videolingo.entity.Subtitle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubtitleRepository extends JpaRepository<Subtitle, Long>, JpaSpecificationExecutor<Subtitle> {

    boolean existsByVideoIdAndLabelIgnoreCase(Long videoId, String label);

    boolean existsByVideoIdAndLabelIgnoreCaseAndIdNot(Long videoId, String label, Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Subtitle s set s.isDefault = false where s.videoId = :videoId and s.isDefault = true")
    void clearDefaultForVideo(@Param("videoId") Long videoId);
}
