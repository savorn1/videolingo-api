package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.webhook.WebhookEvents;
import com.example.videolingo.webhook.WebhookService;
import com.example.videolingo.webhook.WebhookService.DeliveryResponse;
import com.example.videolingo.webhook.WebhookService.WebhookRequest;
import com.example.videolingo.webhook.WebhookService.WebhookResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Outgoing webhooks, module "webhooks" (GET = READ, the rest WRITE).
@RestController
@RequestMapping("/api/admin/webhooks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class WebhookController {

    private final WebhookService webhookService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<WebhookResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(webhookService.list()));
    }

    // The events a webhook can subscribe to.
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<String>>> events() {
        return ResponseEntity.ok(ApiResponse.success(WebhookEvents.ALL));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WebhookResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(webhookService.get(id)));
    }

    @GetMapping("/{id}/deliveries")
    public ResponseEntity<ApiResponse<List<DeliveryResponse>>> deliveries(
            @PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(webhookService.deliveries(id, limit)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WebhookResponse>> create(
            @Valid @RequestBody WebhookRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Webhook created", webhookService.create(request, requireUsername(authentication))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WebhookResponse>> update(
            @PathVariable Long id, @Valid @RequestBody WebhookRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Webhook saved", webhookService.update(id, request)));
    }

    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<ApiResponse<WebhookResponse>> rotateSecret(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "New signing secret created — update your receiver", webhookService.rotateSecret(id)));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<DeliveryResponse>> test(@PathVariable Long id, Authentication authentication) {
        DeliveryResponse result = webhookService.sendTest(id, requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success(result.success() ? "Test delivered" : "Test failed", result));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        webhookService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Webhook deleted", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
