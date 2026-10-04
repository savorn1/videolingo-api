package com.example.videolingo.repository;

import com.example.videolingo.entity.Tag;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, Long>, JpaSpecificationExecutor<Tag> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    // Autocomplete: names starting with the prefix first, then ones containing
    // it anywhere; shorter (closer) names first within each group.
    @Query("""
            select t from Tag t
            where lower(t.name) like concat('%', :q, '%') escape '\\'
            order by case when lower(t.name) like concat(:q, '%') escape '\\' then 0 else 1 end, length(t.name), t.name
            """)
    List<Tag> suggest(@Param("q") String escapedLowerQuery, Pageable pageable);
}
