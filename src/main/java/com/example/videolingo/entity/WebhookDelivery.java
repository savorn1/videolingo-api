package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDateTime;

// One attempt to deliver one message to a Webhook. Retries of the same
// message share `messageId`. Only the newest WebhookService.KEEP_DELIVERIES
// per webhook are kept.
@Entity
@Table(name = "webhook_deliveries", indexes = @Index(name = "idx_webhook_deliveries_webhook", columnList = "webhook_id, id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "webhook_id", nullable = false)
    private Long webhookId;

    @Column(name = "message_id", nullable = false, length = 40)
    private String messageId;

    @Column(nullable = false, length = 60)
    private String event;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    // 0 = no HTTP response.
    @Column(nullable = false)
    private int status;

    @Column(nullable = false)
    private boolean success;

    // Why it failed, or the start of the receiver's reply.
    @Column(length = 500)
    private String detail;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
