package com.example.videolingo.repository;

import com.example.videolingo.entity.Glossary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GlossaryRepository extends JpaRepository<Glossary, Long>, JpaSpecificationExecutor<Glossary> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    // Enabled glossaries into `target`, from `source` or from any language.
    // Source-specific ones first, so their terms win a clash (GlossaryService.termsFor).
    @Query("""
            select g from Glossary g
            where g.enabled = true and lower(g.targetLanguage) = lower(:target)
              and (g.sourceLanguage is null or lower(g.sourceLanguage) = lower(:source))
            order by case when g.sourceLanguage is null then 1 else 0 end, g.id
            """)
    List<Glossary> applicable(@Param("source") String source, @Param("target") String target);

    // Same, when the source language isn't known (checking a finished subtitle track).
    @Query("""
            select g from Glossary g
            where g.enabled = true and lower(g.targetLanguage) = lower(:target)
            order by case when g.sourceLanguage is null then 1 else 0 end, g.id
            """)
    List<Glossary> applicableToTarget(@Param("target") String target);
}
