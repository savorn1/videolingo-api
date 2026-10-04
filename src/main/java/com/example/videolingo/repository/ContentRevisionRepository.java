package com.example.videolingo.repository;

import com.example.videolingo.entity.ContentRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentRevisionRepository extends JpaRepository<ContentRevision, Long> {

    // Everything but the snapshot text, newest first — the history list.
    interface Summary {
        Long getId();

        int getNumber();

        String getSummary();

        int getItemCount();

        String getCreatedBy();

        java.time.LocalDateTime getCreatedAt();
    }

    @Query(
            """
            select r.id as id, r.number as number, r.summary as summary, r.itemCount as itemCount,
                   r.createdBy as createdBy, r.createdAt as createdAt
            from ContentRevision r where r.entityType = :type and r.entityId = :entityId order by r.number desc
            """)
    List<Summary> history(@Param("type") ContentRevision.EntityType type, @Param("entityId") Long entityId);

    @Query(
            "select coalesce(max(r.number), 0) from ContentRevision r where r.entityType = :type and r.entityId = :entityId")
    int maxNumber(@Param("type") ContentRevision.EntityType type, @Param("entityId") Long entityId);

    Optional<ContentRevision> findByIdAndEntityTypeAndEntityId(Long id, ContentRevision.EntityType type, Long entityId);

    // Keeps the newest `keep`: deletes every revision numbered at or below `number`.
    @Modifying
    @Query(
            "delete from ContentRevision r where r.entityType = :type and r.entityId = :entityId and r.number <= :number")
    int deleteUpTo(
            @Param("type") ContentRevision.EntityType type,
            @Param("entityId") Long entityId,
            @Param("number") int number);

    @Modifying
    @Query("delete from ContentRevision r where r.entityType = :type and r.entityId = :entityId")
    void deleteAllOf(@Param("type") ContentRevision.EntityType type, @Param("entityId") Long entityId);
}
