package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.NotificationDtos.BatchFilter;
import com.example.videolingo.dto.NotificationDtos.BatchResponse;
import com.example.videolingo.dto.NotificationDtos.NotificationFilter;
import com.example.videolingo.dto.NotificationDtos.NotificationResponse;
import com.example.videolingo.dto.NotificationDtos.PreviewResponse;
import com.example.videolingo.dto.NotificationDtos.SendRequest;
import com.example.videolingo.dto.NotificationDtos.StatusResponse;
import com.example.videolingo.dto.NotificationDtos.TemplateFilter;
import com.example.videolingo.dto.NotificationDtos.TemplateRequest;
import com.example.videolingo.dto.NotificationDtos.TemplateResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.notification.NotificationService;
import com.example.videolingo.notification.NotificationTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Admin notifications, gated as module "notifications" (GET = READ, the rest —
// sending, previewing, resending and template changes — WRITE). A user's own
// inbox is MyNotificationController.
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationTemplateService templateService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<StatusResponse>> status() {
        return ResponseEntity.ok(ApiResponse.success(notificationService.status()));
    }

    // ── notifications ─────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> list(@ModelAttribute NotificationFilter filter) {
        return ResponseEntity.ok(notificationService.list(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(notificationService.get(id)));
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<BatchResponse>> send(
            @Valid @RequestBody SendRequest request, Authentication authentication) {
        BatchResponse batch = notificationService.send(request, username(authentication));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Sent to " + batch.recipientCount() + (batch.recipientCount() == 1 ? " user" : " users"),
                        batch));
    }

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<PreviewResponse>> preview(
            @Valid @RequestBody SendRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(notificationService.preview(request, username(authentication))));
    }

    @PostMapping("/{id}/resend")
    public ResponseEntity<ApiResponse<NotificationResponse>> resend(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success("Resending", notificationService.resend(id, username(authentication))));
    }

    // ── history (one entry per send) ──────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<PageResponse<BatchResponse>> history(@ModelAttribute BatchFilter filter) {
        return ResponseEntity.ok(notificationService.history(filter));
    }

    @GetMapping("/history/{batchId}")
    public ResponseEntity<ApiResponse<BatchResponse>> batch(@PathVariable Long batchId) {
        return ResponseEntity.ok(ApiResponse.success(notificationService.batch(batchId)));
    }

    // ── templates ─────────────────────────────────────────────────────────

    @GetMapping("/templates")
    public ResponseEntity<PageResponse<TemplateResponse>> templates(@ModelAttribute TemplateFilter filter) {
        return ResponseEntity.ok(templateService.list(filter));
    }

    @GetMapping("/templates/{id}")
    public ResponseEntity<ApiResponse<TemplateResponse>> template(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(templateService.get(id)));
    }

    @PostMapping("/templates")
    public ResponseEntity<ApiResponse<TemplateResponse>> createTemplate(
            @Valid @RequestBody TemplateRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Template created", templateService.create(request, username(authentication))));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateTemplate(
            @PathVariable Long id, @Valid @RequestBody TemplateRequest request, Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success("Template updated", templateService.update(id, request, username(authentication))));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Template deleted", null));
    }

    private static String username(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
