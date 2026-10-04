package com.example.videolingo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AiUsageSummaryResponse(
        LocalDate from,
        LocalDate to,
        long requests,
        long errors,
        long refusals,
        long inputTokens,
        long outputTokens,
        long cacheWriteTokens,
        long cacheReadTokens,
        BigDecimal costUsd,
        BigDecimal avgCostPerRequestUsd,
        Double avgLatencyMs,
        // Share of all input-side tokens served from cache (0–1).
        double cacheHitRate,
        // Net USD saved by caching (reads vs. full price, minus write premium).
        BigDecimal cacheSavingsUsd,
        long unpricedRequests,
        List<Group> byFeature,
        List<Group> byModel,
        List<Group> byUser,
        List<Day> daily) {

    public record Group(
            String key,
            long requests,
            long inputTokens,
            long outputTokens,
            long cacheWriteTokens,
            long cacheReadTokens,
            BigDecimal costUsd) {}

    // Zero-filled: one entry per day in the range.
    public record Day(LocalDate date, long requests, BigDecimal costUsd) {}
}
