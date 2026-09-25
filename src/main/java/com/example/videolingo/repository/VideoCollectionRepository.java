package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VideoCollectionRepository extends JpaRepository<VideoCollection, Long>, JpaSpecificationExecutor<VideoCollection> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);
}
