package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One "Send notification" action — the unit shown in Notification History.
// Its per-recipient, per-channel deliveries are Notification rows (batchId).
// Template fields are snapshots, so history survives the template's deletion.
@Entity
@Table(name = "notification_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "template_code", length = 60)
    private String templateCode;

    @Column(name = "template_name", length = 100)
    private String templateName;

    // As written (before per-recipient variables are filled in).
    @Column(nullable = false, length = 200)
    private String subject;

    // Comma-separated NotificationChannel names, e.g. "IN_APP,EMAIL".
    @Column(nullable = false, length = 40)
    private String channels;

    // Human-readable audience: "All users", "Role: ADMIN", "3 selected users".
    @Column(nullable = false, length = 100)
    private String audience;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "sent_by", length = 50)
    private String sentBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
