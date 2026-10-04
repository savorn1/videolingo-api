package com.example.videolingo.dto;

import com.example.videolingo.entity.NotificationChannel;
import com.example.videolingo.entity.NotificationEventType;
import com.example.videolingo.entity.NotificationStatus;
import com.example.videolingo.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;

public final class NotificationDtos {

    private NotificationDtos() {}

    // ── templates ─────────────────────────────────────────────────────────

    @Data
    public static class TemplateRequest {
        @NotBlank
        @Size(max = 60)
        @Pattern(
                regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "use lower-case letters, digits and single hyphens, e.g. new-course")
        private String code;

        @NotBlank
        @Size(max = 100)
        private String name;

        @Size(max = 300)
        private String description;

        @NotBlank
        @Size(max = 200)
        private String subject;

        @NotBlank
        @Size(max = 10_000)
        private String body;

        @NotEmpty
        private Set<NotificationChannel> defaultChannels = new LinkedHashSet<>();
    }

    public record TemplateResponse(
            Long id,
            String code,
            String name,
            String description,
            String subject,
            String body,
            List<NotificationChannel> defaultChannels,
            // Custom {{variables}} a sender must fill in (built-ins excluded).
            List<String> variables,
            // How many sends used this template.
            long usageCount,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    @Data
    @ParameterObject
    public static class TemplateFilter {
        // Code, name or subject.
        private String search;
        private NotificationChannel channel;
        private String sortBy = "name";
        private String sortOrder = "asc";
        private int page = 1;
        private int size = 25;
    }

    // ── sending ───────────────────────────────────────────────────────────

    // Content comes from the template unless subject/body are given (they
    // override it — the sender edited the text). Audience: allUsers, else role,
    // else userIds; only enabled accounts receive anything.
    @Data
    public static class SendRequest {
        private Long templateId;

        @Size(max = 200)
        private String subject;

        @Size(max = 10_000)
        private String body;

        @NotEmpty
        private Set<NotificationChannel> channels = new LinkedHashSet<>();

        private List<Long> userIds;
        private Role role;
        private boolean allUsers;
        private Map<String, String> variables = new LinkedHashMap<>();
    }

    public record PreviewResponse(
            String subject,
            String body,
            // Whose built-ins the preview used (null when nobody is selected yet).
            String sampleRecipient,
            int recipientCount,
            // Selected recipients with no email address (their email copy will fail).
            int recipientsWithoutEmail,
            int disabledSkipped,
            List<String> variables,
            List<String> missingVariables) {}

    // ── notifications ─────────────────────────────────────────────────────

    public record EventDto(Long id, NotificationEventType type, String detail, String actor, LocalDateTime createdAt) {}

    public record NotificationResponse(
            Long id,
            Long batchId,
            Long recipientId,
            String recipientUsername,
            String recipientEmail,
            NotificationChannel channel,
            NotificationStatus status,
            Long templateId,
            String templateCode,
            String subject,
            // Full body on detail/inbox; first ~160 chars in admin lists.
            String body,
            boolean bodyTruncated,
            int attempts,
            String errorMessage,
            String sentBy,
            LocalDateTime createdAt,
            LocalDateTime sentAt,
            LocalDateTime readAt,
            // Detail only.
            List<EventDto> events) {}

    @Data
    @ParameterObject
    public static class NotificationFilter {
        // Subject or recipient username/email.
        private String search;
        private Long recipientId;
        private NotificationChannel channel;
        private NotificationStatus status;
        private Long batchId;
        private Long templateId;
        // In-app read state: true = read, false = unread.
        private Boolean read;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;

        private String sortBy = "createdAt";
        private String sortOrder = "desc";
        private int page = 1;
        private int size = 25;
    }

    // ── history (one row per send) ────────────────────────────────────────

    public record BatchResponse(
            Long id,
            Long templateId,
            String templateCode,
            String templateName,
            String subject,
            List<NotificationChannel> channels,
            String audience,
            int recipientCount,
            String sentBy,
            LocalDateTime createdAt,
            long total,
            long sent,
            long failed,
            long pending,
            long read,
            long inApp) {}

    @Data
    @ParameterObject
    public static class BatchFilter {
        // Subject, template name/code or sender.
        private String search;
        private Long templateId;
        private String sentBy;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;

        private String sortBy = "createdAt";
        private String sortOrder = "desc";
        private int page = 1;
        private int size = 25;
    }

    public record StatusResponse(boolean emailConfigured, String emailFrom, List<String> builtInVariables) {}

    public record InboxCount(long unread) {}
}
