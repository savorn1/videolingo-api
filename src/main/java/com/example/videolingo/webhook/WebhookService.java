package com.example.videolingo.webhook;

import com.example.videolingo.entity.Webhook;
import com.example.videolingo.entity.WebhookDelivery;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.ingest.SafeHttp;
import com.example.videolingo.notification.NotificationService;
import com.example.videolingo.repository.WebhookDeliveryRepository;
import com.example.videolingo.repository.WebhookRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Webhook management and delivery. Delivery: POST the JSON message, signed
// (WebhookSigner), no redirects followed, 10 s timeout; any 2xx is success.
// Failed messages are retried twice more (after 5 s, then 30 s). URLs must
// resolve to public addresses (SafeHttp) — checked when saved and again
// before every attempt, since DNS can change.
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    static final int KEEP_DELIVERIES = 100;
    /** Failed attempts in a row (≈ 7 messages with retries) before a webhook is switched off. */
    static final int PAUSE_AFTER_FAILURES = 20;
    private static final long[] RETRY_DELAYS_MS = {5_000, 30_000};
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public record WebhookRequest(@NotBlank @Size(max = 100) String name,
                                 @NotBlank @Size(max = 1000) String url,
                                 @NotEmpty List<String> events,
                                 boolean enabled) {
    }

    public record WebhookResponse(Long id, String name, String url, String secret, List<String> events, boolean enabled,
                                  LocalDateTime lastDeliveryAt, Integer lastStatus, int consecutiveFailures, String createdBy,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record DeliveryResponse(Long id, String messageId, String event, int attempt, int status, boolean success, String detail,
                                   long durationMs, String payload, LocalDateTime createdAt) {
    }

    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notifications;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ── Management ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WebhookResponse> list() {
        return webhookRepository.findAllByOrderByIdDesc().stream().map(WebhookService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WebhookResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public WebhookResponse create(WebhookRequest request, String actor) {
        Webhook webhook = Webhook.builder().secret(WebhookSigner.newSecret()).createdBy(actor).build();
        apply(webhook, request);
        return toResponse(webhookRepository.save(webhook));
    }

    @Transactional
    public WebhookResponse update(Long id, WebhookRequest request) {
        Webhook webhook = find(id);
        apply(webhook, request);
        if (webhook.isEnabled()) {
            webhook.setConsecutiveFailures(0);
        }
        return toResponse(webhookRepository.save(webhook));
    }

    @Transactional
    public WebhookResponse rotateSecret(Long id) {
        Webhook webhook = find(id);
        webhook.setSecret(WebhookSigner.newSecret());
        return toResponse(webhookRepository.save(webhook));
    }

    @Transactional
    public void delete(Long id) {
        Webhook webhook = find(id);
        deliveryRepository.deleteByWebhookId(id);
        webhookRepository.delete(webhook);
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> deliveries(Long id, int limit) {
        find(id);
        return deliveryRepository.findByWebhookIdOrderByIdDesc(id, PageRequest.of(0, Math.max(1, Math.min(limit, KEEP_DELIVERIES)))).stream()
                .map(d -> new DeliveryResponse(d.getId(), d.getMessageId(), d.getEvent(), d.getAttempt(), d.getStatus(), d.isSuccess(),
                        d.getDetail(), d.getDurationMs(), d.getPayload(), d.getCreatedAt()))
                .toList();
    }

    /** Sends one "webhook.test" message now (no retries) and returns how it went. */
    public DeliveryResponse sendTest(Long id, String actor) {
        Webhook webhook = find(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("webhookId", webhook.getId());
        data.put("sentBy", actor);
        data.put("message", "This is a test message from VideoLingo.");
        String messageId = UUID.randomUUID().toString();
        WebhookDelivery d = attempt(webhook, messageId, WebhookEvents.TEST, message(messageId, WebhookEvents.TEST, data), 1);
        return new DeliveryResponse(d.getId(), d.getMessageId(), d.getEvent(), d.getAttempt(), d.getStatus(), d.isSuccess(), d.getDetail(),
                d.getDurationMs(), d.getPayload(), d.getCreatedAt());
    }

    // ── Delivery ──────────────────────────────────────────────────────────

    /** Delivers `event` to every enabled webhook subscribed to it. Blocking — call from a background thread. */
    public void dispatch(String event, Map<String, Object> data) {
        for (Webhook webhook : webhookRepository.findByEnabledTrue()) {
            if (!eventsOf(webhook).contains(event)) {
                continue;
            }
            String messageId = UUID.randomUUID().toString();
            String body = message(messageId, event, data);
            for (int attempt = 1; attempt <= RETRY_DELAYS_MS.length + 1; attempt++) {
                WebhookDelivery d = attempt(webhook, messageId, event, body, attempt);
                if (d.isSuccess() || attempt > RETRY_DELAYS_MS.length) {
                    break;
                }
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt - 1]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Disabled or deleted while we waited: stop retrying.
                var current = webhookRepository.findById(webhook.getId());
                if (current.isEmpty() || !current.get().isEnabled()) {
                    break;
                }
                webhook = current.get();
            }
        }
    }

    private WebhookDelivery attempt(Webhook webhook, String messageId, String event, String body, int attempt) {
        long started = System.nanoTime();
        int status = 0;
        String detail;
        try {
            URI uri = checkedUri(webhook.getUrl());
            long now = Instant.now().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "VideoLingo-Webhook/1")
                    .header("X-VideoLingo-Event", event)
                    .header("X-VideoLingo-Delivery", messageId)
                    .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.signature(webhook.getSecret(), now, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            String reply = response.body() == null ? "" : response.body().strip();
            detail = status >= 300 && status < 400 ? "Redirects aren't followed — use the final URL"
                    : reply.isEmpty() ? null : reply.length() > 300 ? reply.substring(0, 300) + "…" : reply;
        } catch (AppException e) {
            detail = e.getMessage();
        } catch (IOException e) {
            detail = "No response: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detail = "Interrupted";
        }
        boolean success = status >= 200 && status < 300;
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.builder()
                .webhookId(webhook.getId())
                .messageId(messageId)
                .event(event)
                .attempt(attempt)
                .payload(body)
                .status(status)
                .success(success)
                .detail(detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail)
                .durationMs((System.nanoTime() - started) / 1_000_000)
                .build());
        webhookRepository.findById(webhook.getId()).ifPresent(w -> {
            w.setLastDeliveryAt(LocalDateTime.now());
            w.setLastStatus(delivery.getStatus());
            w.setConsecutiveFailures(success ? 0 : w.getConsecutiveFailures() + 1);
            boolean pause = w.isEnabled() && w.getConsecutiveFailures() >= PAUSE_AFTER_FAILURES;
            if (pause) {
                w.setEnabled(false);
            }
            webhookRepository.save(w);
            if (pause) {
                announcePause(w, delivery);
            }
        });
        deliveryRepository.prune(webhook.getId(), KEEP_DELIVERIES);
        return delivery;
    }

    // Tell admins, so a dead receiver doesn't go unnoticed (and stops being hammered).
    private void announcePause(Webhook w, WebhookDelivery last) {
        log.warn("Webhook #{} ({}) paused after {} failed deliveries in a row", w.getId(), w.getUrl(), w.getConsecutiveFailures());
        try {
            notifications.notifyAdmins("Webhook paused: " + w.getName(),
                    "The webhook “" + w.getName() + "” (" + w.getUrl() + ") failed " + w.getConsecutiveFailures()
                            + " times in a row, so it was switched off. Last result: "
                            + (last.getStatus() == 0 ? "no response" : "HTTP " + last.getStatus())
                            + (last.getDetail() != null ? " — " + last.getDetail() : "")
                            + ".\n\nFix the receiver, then turn it back on under Administration › Webhooks.", "system");
        } catch (RuntimeException e) {
            log.warn("Couldn't notify admins about paused webhook #{}", w.getId(), e);
        }
    }

    private String message(String messageId, String event, Map<String, Object> data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("event", event);
        message.put("createdAt", Instant.now().toString());
        message.put("data", data);
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void apply(Webhook webhook, WebhookRequest request) {
        String url = request.url().strip();
        checkedUri(url);
        Set<String> events = new LinkedHashSet<>();
        for (String e : request.events()) {
            if (!WebhookEvents.ALL.contains(e)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Unknown event \"" + e + "\"");
            }
            events.add(e);
        }
        webhook.setName(request.name().strip());
        webhook.setUrl(url);
        webhook.setEvents(String.join(",", events));
        webhook.setEnabled(request.enabled());
    }

    private static URI checkedUri(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "That isn't a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Webhook URLs must start with https:// (or http://)");
        }
        try {
            SafeHttp.checkAllowed(uri);
        } catch (SafeHttp.BlockedException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Webhooks can only call public web addresses on the standard ports (80/443): "
                    + e.getMessage().substring(0, 1).toLowerCase() + e.getMessage().substring(1));
        }
        return uri;
    }

    private Webhook find(Long id) {
        return webhookRepository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Webhook not found with id: " + id));
    }

    private static List<String> eventsOf(Webhook w) {
        return w.getEvents() == null || w.getEvents().isBlank() ? List.of() : Arrays.asList(w.getEvents().split(","));
    }

    private static WebhookResponse toResponse(Webhook w) {
        return new WebhookResponse(w.getId(), w.getName(), w.getUrl(), w.getSecret(), eventsOf(w), w.isEnabled(), w.getLastDeliveryAt(),
                w.getLastStatus(), w.getConsecutiveFailures(), w.getCreatedBy(), w.getCreatedAt(), w.getUpdatedAt());
    }
}
