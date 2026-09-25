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

// One change-making API request: who, what (method + path), and how it went
// (HTTP status). Written by AuditFilter for every request AuditPolicy says
// to keep — reads aren't logged. Request bodies are never stored.
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_logs_username", columnList = "username")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Null when nobody was signed in (a failed login shows the name tried in `detail`).
    @Column(length = 100)
    private String username;

    // "API key" when the request authenticated with one, else "session".
    @Column(name = "auth_type", length = 20)
    private String authType;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 500)
    private String path;

    // "videos", "subtitles", "auth", "profile"… (see AuditPolicy.moduleOf).
    @Column(nullable = false, length = 60)
    private String module;

    // READ / WRITE / APPROVE for /api/admin/**, like the permission check.
    @Column(length = 20)
    private String action;

    // The first numeric id in the path after the module, e.g. 12 for /videos/12/tags.
    @Column(name = "entity_id")
    private Long entityId;

    @Column(nullable = false)
    private int status;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    // Extra context, e.g. the username a failed login tried.
    @Column(length = 300)
    private String detail;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
