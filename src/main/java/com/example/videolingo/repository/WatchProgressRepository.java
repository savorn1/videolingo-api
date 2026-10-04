package com.example.videolingo.repository;

import com.example.videolingo.entity.WatchProgress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchProgressRepository extends JpaRepository<WatchProgress, Long> {

    Optional<WatchProgress> findByUserIdAndVideoId(Long userId, Long videoId);

    List<WatchProgress> findByUserIdAndVideoIdIn(Long userId, Collection<Long> videoIds);

    // Every learner's progress on these videos, any user — for collection analytics.
    List<WatchProgress> findByVideoIdIn(Collection<Long> videoIds);

    // Started but not finished, on videos that still exist and aren't trashed — newest first.
    @Query(
            """
            select p from WatchProgress p, Video v
            where v.id = p.videoId and v.deletedAt is null
              and p.userId = :userId and p.completed = false and p.positionSeconds >= :minPosition
            order by p.lastWatchedAt desc
            """)
    List<WatchProgress> inProgress(
            @Param("userId") Long userId, @Param("minPosition") int minPosition, Pageable pageable);

    void deleteByUserIdAndVideoId(Long userId, Long videoId);
}
