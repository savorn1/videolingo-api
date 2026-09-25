package com.example.videolingo.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

// The editable settings, one record per section. Field-level limits are
// enforced by Bean Validation on update; cross-field and cross-table checks
// (known languages, upload caps vs the server's multipart limit…) live in
// SettingsService.
public final class Settings {

    private Settings() {
    }

    public record General(
            @NotBlank @Size(max = 60) String siteName,
            // Reply-to on outgoing email; blank = none.
            @Email @Size(max = 120) String supportEmail,
            // Base URL of the admin app used in emailed links and {{appUrl}}; blank = the server's app.frontend-url.
            @Size(max = 200) @Pattern(regexp = "^$|^https?://\\S+$", message = "must start with http:// or https://") String publicUrl) {
    }

    public record Video(
            @Min(1) @Max(100) int maxTagsPerVideo,
            @Min(1) @Max(50) int maxCategoriesPerVideo,
            // Saving a video's details requires at least one category.
            boolean requireCategory,
            // Window the video page's statistics open with.
            @Min(7) @Max(365) int statisticsDefaultDays,
            // Uploaded video files go straight to storage, so this isn't bound by the request cap.
            @Min(1) @Max(51200) int maxVideoUploadMb) {
    }

    public record SubtitleRulesSetting(
            @Min(10) @Max(100) int maxCharsPerLine,
            @Min(1) @Max(4) int maxLines,
            @Min(200) @Max(5000) long minDurationMs,
            @Min(1000) @Max(20000) long maxDurationMs,
            @DecimalMin("5.0") @DecimalMax("40.0") double maxCps) {

        @JsonIgnore
        @AssertTrue(message = "the maximum duration must be longer than the minimum")
        public boolean isDurationOrdered() {
            return maxDurationMs > minDurationMs;
        }
    }

    public record Translation(
            // Pre-selected when creating a translation; must be enabled languages.
            @NotNull @Size(max = 20) List<String> defaultTargetLanguages,
            @NotNull @Valid SubtitleRulesSetting standardRules,
            // For scripts that pack more per character (Chinese, Japanese…).
            @NotNull @Valid SubtitleRulesSetting compactRules,
            // Primary language subtags that use compactRules, e.g. "ja", "zh".
            @NotNull @Size(max = 30) List<@Pattern(regexp = "^[a-z]{2,3}$", message = "must be a 2–3 letter language code") String> compactLanguages,
            // Refuse to publish a track that still has readability issues.
            boolean blockPublishWithIssues,
            // Refuse to publish a track until a reviewer has approved it.
            boolean requireApprovalToPublish) {
    }

    public record Ai(
            // Master switch — off makes every AI endpoint answer 503.
            boolean enabled,
            boolean summaryEnabled,
            boolean chaptersEnabled,
            boolean keyPointsEnabled,
            boolean questionsEnabled,
            boolean quizEnabled,
            boolean chatEnabled,
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9][a-z0-9.\\-]*$", message = "must be a model id such as claude-opus-5") String model,
            // Blank = no retry on a refusal.
            @Size(max = 80) @Pattern(regexp = "^$|^[a-z0-9][a-z0-9.\\-]*$", message = "must be a model id such as claude-opus-4-8") String fallbackModel,
            @NotNull @Pattern(regexp = "^(low|medium|high|xhigh|max)$", message = "must be low, medium, high, xhigh or max") String generationEffort,
            @NotNull @Pattern(regexp = "^(low|medium|high|xhigh|max)$", message = "must be low, medium, high, xhigh or max") String chatEffort,
            // Null = no budget.
            @DecimalMin("0.0") @DecimalMax("1000000.0") BigDecimal monthlyBudgetUsd,
            boolean budgetEnforced,
            @Min(200) @Max(20000) int maxChatMessageChars,
            @Min(1) @Max(20) int defaultKeyPoints,
            @Min(1) @Max(20) int defaultQuestions,
            @Min(1) @Max(20) int defaultQuizQuestions) {
    }

    public record Storage(
            // /api/files/upload
            @Min(1) @Max(10240) int maxUploadMb,
            // MIME types or families ("image/*"); empty = anything.
            @NotNull @Size(max = 40) List<@Pattern(regexp = "^[a-z]+/([a-z0-9.+\\-]+|\\*)$", message = "must be a MIME type like image/png or image/*") String> allowedUploadTypes,
            @Min(1) @Max(1024) int maxSubtitleUploadMb,
            // Shown against stored bytes in Analytics; null = no quota.
            @DecimalMin("0.1") @DecimalMax("1000000.0") Double storageQuotaGb) {
    }
}
