package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// One row per Claude API call — successful or not — with its token usage and
// cost. The source for AI usage and cost reporting. Failed calls are recorded
// too (tokens 0 when the call never completed) so errors show up in the stats.
@Entity
@Table(name = "ai_usage", indexes = {
        @Index(name = "idx_ai_usage_created", columnList = "created_at"),
        @Index(name = "idx_ai_usage_video", columnList = "video_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiFeature feature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiUsageStatus status;

    // The model the response came from (what was billed).
    @Column(nullable = false, length = 60)
    private String model;

    // Set when this call was a retry after another model declined.
    @Column(name = "fallback_from", length = 60)
    private String fallbackFrom;

    @Column(name = "video_id")
    private Long videoId;

    @Column(name = "transcript_id")
    private Long transcriptId;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(length = 100)
    private String username;

    @Builder.Default
    @Column(name = "input_tokens", nullable = false)
    private long inputTokens = 0;

    @Builder.Default
    @Column(name = "output_tokens", nullable = false)
    private long outputTokens = 0;

    @Builder.Default
    @Column(name = "cache_write_tokens", nullable = false)
    private long cacheWriteTokens = 0;

    @Builder.Default
    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens = 0;

    // Null when the model has no configured price.
    @Column(name = "cost_usd", precision = 14, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "latency_ms")
    private Long latencyMs;

    // Anthropic message id, for matching a row to the Console / support requests.
    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "stop_reason", length = 30)
    private String stopReason;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
