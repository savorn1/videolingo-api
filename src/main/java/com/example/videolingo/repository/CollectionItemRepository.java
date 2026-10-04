package com.example.videolingo.repository;

import com.example.videolingo.entity.CollectionItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionItemRepository extends JpaRepository<CollectionItem, Long> {

    Page<CollectionItem> findByCollectionIdOrderByPositionAsc(Long collectionId, Pageable pageable);

    List<CollectionItem> findByCollectionIdOrderByPositionAsc(Long collectionId);

    Optional<CollectionItem> findByCollectionIdAndVideoId(Long collectionId, Long videoId);

    @Query("select i.videoId from CollectionItem i where i.collectionId = :collectionId")
    List<Long> findVideoIds(@Param("collectionId") Long collectionId);

    @Query("select coalesce(max(i.position), -1) from CollectionItem i where i.collectionId = :collectionId")
    int maxPosition(@Param("collectionId") Long collectionId);

    // Close the gap a removal leaves, so positions stay 0..n-1.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update CollectionItem i set i.position = i.position - 1 where i.collectionId = :collectionId and i.position > :position")
    void shiftDownAfter(@Param("collectionId") Long collectionId, @Param("position") int position);

    @Modifying
    @Query("delete from CollectionItem i where i.collectionId = :collectionId")
    void deleteByCollectionId(@Param("collectionId") Long collectionId);

    interface DurationSum {
        long getTotalSeconds();

        long getTrashed();

        long getDisabled();
    }

    // Totals over the collection's videos (trashed ones excluded from duration).
    @Query(value = """
            select coalesce(sum(case when v.deleted_at is null then v.duration_seconds else 0 end), 0) as totalSeconds,
                   count(*) filter (where v.deleted_at is not null) as trashed,
                   count(*) filter (where v.deleted_at is null and not v.enabled) as disabled
            from collection_items i join videos v on v.id = i.video_id
            where i.collection_id = :collectionId
            """, nativeQuery = true)
    DurationSum totals(@Param("collectionId") Long collectionId);
}
