package com.example.videolingo.repository;

import com.example.videolingo.entity.VideoView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface VideoViewRepository extends JpaRepository<VideoView, Long> {

    interface ViewTotals {
        long getTotalViews();
        long getUniqueViewers();
        long getTotalWatchSeconds();
        long getCompletedViews();
        LocalDateTime getLastViewedAt();
    }

    interface VideoViewCount {
        Long getVideoId();
        long getViews();
    }

    interface DailyViews {
        LocalDate getDay();
        long getViews();
    }

    @Query("""
            select count(v) as totalViews,
                   count(distinct v.userId) as uniqueViewers,
                   coalesce(sum(v.watchedSeconds), 0) as totalWatchSeconds,
                   coalesce(sum(case when v.completed = true then 1 else 0 end), 0) as completedViews,
                   max(v.viewedAt) as lastViewedAt
            from VideoView v
            where v.videoId = :videoId
            """)
    ViewTotals totalsFor(@Param("videoId") Long videoId);

    // Batch view counts for a page of the video list (one query, not one per row).
    @Query("select v.videoId as videoId, count(v) as views from VideoView v where v.videoId in :videoIds group by v.videoId")
    List<VideoViewCount> countByVideoIds(@Param("videoIds") Collection<Long> videoIds);

    @Query(value = """
            select cast(viewed_at as date) as day, count(*) as views
            from video_views
            where video_id = :videoId and viewed_at >= :since
            group by cast(viewed_at as date)
            order by day
            """, nativeQuery = true)
    List<DailyViews> dailyViewsSince(@Param("videoId") Long videoId, @Param("since") LocalDateTime since);

    java.util.Optional<VideoView> findBySessionIdAndVideoId(String sessionId, Long videoId);
}
