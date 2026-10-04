package com.example.videolingo.repository;

import com.example.videolingo.entity.GlossaryTerm;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, Long> {

    List<GlossaryTerm> findByGlossaryIdOrderByPositionAsc(Long glossaryId);

    List<GlossaryTerm> findByGlossaryIdInOrderByGlossaryIdAscPositionAsc(Collection<Long> glossaryIds);

    @Modifying
    @Query("delete from GlossaryTerm t where t.glossaryId = :glossaryId")
    void deleteByGlossaryId(@Param("glossaryId") Long glossaryId);
}
