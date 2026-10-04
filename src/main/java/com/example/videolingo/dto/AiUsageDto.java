package com.example.videolingo.dto;

import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.entity.AiUsageStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AiUsageDto(
        Long id,
        AiFeature feature,
        AiUsageStatus status,
        String model,
        String fallbackFrom,
        Long videoId,
        Long transcriptId,
        Long chatId,
        String username,
        long inputTokens,
        long outputTokens,
        long cacheWriteTokens,
        long cacheReadTokens,
        BigDecimal costUsd,
        Long latencyMs,
        String requestId,
        String stopReason,
        String errorMessage,
        LocalDateTime createdAt) {

    public static AiUsageDto of(AiUsageRecord u) {
        return u == null
                ? null
                : new AiUsageDto(
                        u.getId(),
                        u.getFeature(),
                        u.getStatus(),
                        u.getModel(),
                        u.getFallbackFrom(),
                        u.getVideoId(),
                        u.getTranscriptId(),
                        u.getChatId(),
                        u.getUsername(),
                        u.getInputTokens(),
                        u.getOutputTokens(),
                        u.getCacheWriteTokens(),
                        u.getCacheReadTokens(),
                        u.getCostUsd(),
                        u.getLatencyMs(),
                        u.getRequestId(),
                        u.getStopReason(),
                        u.getErrorMessage(),
                        u.getCreatedAt());
    }
}
