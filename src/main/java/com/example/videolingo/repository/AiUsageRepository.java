package com.example.videolingo.repository;

import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.entity.AiUsageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageRepository extends JpaRepository<AiUsageRecord, Long>, JpaSpecificationExecutor<AiUsageRecord> {

    @Query("select coalesce(sum(u.costUsd), 0) from AiUsageRecord u where u.createdAt >= :from and u.createdAt < :to")
    BigDecimal sumCost(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    interface Totals {
        long getRequests();
        long getErrors();
        long getRefusals();
        long getInputTokens();
        long getOutputTokens();
        long getCacheWriteTokens();
        long getCacheReadTokens();
        BigDecimal getCost();
        Double getAvgLatencyMs();
        long getUnpriced();
    }

    @Query("""
            select count(u) as requests,
                   coalesce(sum(case when u.status = com.example.videolingo.entity.AiUsageStatus.ERROR or u.status = com.example.videolingo.entity.AiUsageStatus.TRUNCATED then 1 else 0 end), 0) as errors,
                   coalesce(sum(case when u.status = com.example.videolingo.entity.AiUsageStatus.REFUSED then 1 else 0 end), 0) as refusals,
                   coalesce(sum(u.inputTokens), 0) as inputTokens,
                   coalesce(sum(u.outputTokens), 0) as outputTokens,
                   coalesce(sum(u.cacheWriteTokens), 0) as cacheWriteTokens,
                   coalesce(sum(u.cacheReadTokens), 0) as cacheReadTokens,
                   coalesce(sum(u.costUsd), 0) as cost,
                   avg(u.latencyMs) as avgLatencyMs,
                   coalesce(sum(case when u.costUsd is null and u.status <> com.example.videolingo.entity.AiUsageStatus.ERROR then 1 else 0 end), 0) as unpriced
            from AiUsageRecord u where u.createdAt >= :from and u.createdAt < :to
            """)
    Totals totals(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    interface Group {
        String getKey();
        long getRequests();
        long getInputTokens();
        long getOutputTokens();
        long getCacheWriteTokens();
        long getCacheReadTokens();
        BigDecimal getCost();
    }

    @Query("""
            select cast(u.feature as string) as key, count(u) as requests, coalesce(sum(u.inputTokens), 0) as inputTokens,
                   coalesce(sum(u.outputTokens), 0) as outputTokens, coalesce(sum(u.cacheWriteTokens), 0) as cacheWriteTokens,
                   coalesce(sum(u.cacheReadTokens), 0) as cacheReadTokens, coalesce(sum(u.costUsd), 0) as cost
            from AiUsageRecord u where u.createdAt >= :from and u.createdAt < :to group by u.feature order by sum(u.costUsd) desc nulls last
            """)
    List<Group> byFeature(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            select u.model as key, count(u) as requests, coalesce(sum(u.inputTokens), 0) as inputTokens,
                   coalesce(sum(u.outputTokens), 0) as outputTokens, coalesce(sum(u.cacheWriteTokens), 0) as cacheWriteTokens,
                   coalesce(sum(u.cacheReadTokens), 0) as cacheReadTokens, coalesce(sum(u.costUsd), 0) as cost
            from AiUsageRecord u where u.createdAt >= :from and u.createdAt < :to group by u.model order by sum(u.costUsd) desc nulls last
            """)
    List<Group> byModel(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            select coalesce(u.username, '(unknown)') as key, count(u) as requests, coalesce(sum(u.inputTokens), 0) as inputTokens,
                   coalesce(sum(u.outputTokens), 0) as outputTokens, coalesce(sum(u.cacheWriteTokens), 0) as cacheWriteTokens,
                   coalesce(sum(u.cacheReadTokens), 0) as cacheReadTokens, coalesce(sum(u.costUsd), 0) as cost
            from AiUsageRecord u where u.createdAt >= :from and u.createdAt < :to group by u.username order by sum(u.costUsd) desc nulls last
            """)
    List<Group> byUser(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    interface Daily {
        LocalDate getDay();
        long getRequests();
        BigDecimal getCost();
    }

    @Query(value = """
            select cast(created_at as date) as day, count(*) as requests, coalesce(sum(cost_usd), 0) as cost
            from ai_usage where created_at >= :from and created_at < :to
            group by cast(created_at as date) order by day
            """, nativeQuery = true)
    List<Daily> daily(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    long countByFeatureAndStatus(AiFeature feature, AiUsageStatus status);

    // Output sizes of the latest successful calls of one feature (newest first) — AiEstimateService.
    @Query("select u.outputTokens from AiUsageRecord u where u.feature = :feature and u.status = com.example.videolingo.entity.AiUsageStatus.SUCCESS order by u.id desc")
    List<Long> recentOutputTokens(@Param("feature") AiFeature feature, org.springframework.data.domain.Pageable pageable);

    // The latest successful non-chat call on a transcript: its input size is what that transcript measures.
    @Query("select u from AiUsageRecord u where u.transcriptId = :transcriptId and u.status = com.example.videolingo.entity.AiUsageStatus.SUCCESS and u.feature <> com.example.videolingo.entity.AiFeature.CHAT order by u.id desc")
    List<AiUsageRecord> latestOnTranscript(@Param("transcriptId") Long transcriptId, org.springframework.data.domain.Pageable pageable);
}
