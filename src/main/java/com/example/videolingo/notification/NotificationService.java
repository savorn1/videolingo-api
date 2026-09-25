package com.example.videolingo.notification;

import com.example.videolingo.dto.NotificationDtos.BatchFilter;
import com.example.videolingo.dto.NotificationDtos.BatchResponse;
import com.example.videolingo.dto.NotificationDtos.EventDto;
import com.example.videolingo.dto.NotificationDtos.NotificationFilter;
import com.example.videolingo.dto.NotificationDtos.NotificationResponse;
import com.example.videolingo.dto.NotificationDtos.PreviewResponse;
import com.example.videolingo.dto.NotificationDtos.SendRequest;
import com.example.videolingo.dto.NotificationDtos.StatusResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.Notification;
import com.example.videolingo.entity.NotificationBatch;
import com.example.videolingo.entity.NotificationChannel;
import com.example.videolingo.entity.NotificationEvent;
import com.example.videolingo.entity.NotificationEventType;
import com.example.videolingo.entity.NotificationStatus;
import com.example.videolingo.entity.NotificationTemplate;
import com.example.videolingo.entity.Role;
import com.example.videolingo.entity.User;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.NotificationBatchRepository;
import com.example.videolingo.repository.NotificationEventRepository;
import com.example.videolingo.repository.NotificationRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// Send Notification, the notification log, Notification History (one row per
// send) and each user's in-app inbox. Email delivery itself is asynchronous
// (NotificationMailer); everything else happens in the request.
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final int MAX_RECIPIENTS = 10_000;
    private static final int LIST_BODY_PREVIEW = 160;
    private static final Set<String> SORTABLE = Set.of("createdAt", "sentAt", "readAt", "status", "channel", "recipientUsername", "subject");
    private static final Set<String> BATCH_SORTABLE = Set.of("createdAt", "subject", "recipientCount", "sentBy");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final NotificationRepository notificationRepository;
    private final NotificationBatchRepository batchRepository;
    private final NotificationEventRepository eventRepository;
    private final NotificationTemplateService templateService;
    private final NotificationMailer mailer;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final SettingsService settings;

    public StatusResponse status() {
        return new StatusResponse(mailer.configured(), mailer.from(), TemplateRenderer.BUILT_INS);
    }

    // ── Send ──────────────────────────────────────────────────────────────

    private record Content(NotificationTemplate template, String subject, String body) {
    }

    private record Audience(List<User> users, String label, int disabledSkipped) {
    }

    @Transactional(readOnly = true)
    public PreviewResponse preview(SendRequest request, String actor) {
        Content content = content(request, false);
        Audience audience = audience(request);
        Set<String> used = TemplateRenderer.variables(content.subject(), content.body());
        Set<String> missing = TemplateRenderer.missing(TemplateRenderer.customVariables(content.subject(), content.body()), request.getVariables());
        // With nobody chosen yet, preview as the sender.
        User sample = audience.users().isEmpty() ? userRepository.findByUsername(actor).orElse(null) : audience.users().get(0);
        Map<String, String> values = values(sample, request.getVariables());
        int withoutEmail = request.getChannels().contains(NotificationChannel.EMAIL)
                ? (int) audience.users().stream().filter(u -> blank(u.getEmail())).count() : 0;
        return new PreviewResponse(content.subject() == null ? "" : TemplateRenderer.render(content.subject(), values),
                content.body() == null ? "" : TemplateRenderer.render(content.body(), values),
                sample == null ? null : sample.getUsername(), audience.users().size(), withoutEmail, audience.disabledSkipped(),
                List.copyOf(used), List.copyOf(missing));
    }

    @Transactional
    public BatchResponse send(SendRequest request, String actor) {
        Content content = content(request, true);
        Audience audience = audience(request);
        if (audience.users().isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, audience.disabledSkipped() > 0
                    ? "Every selected user is disabled — nobody would receive this" : "Choose at least one recipient");
        }
        Set<String> missing = TemplateRenderer.missing(TemplateRenderer.customVariables(content.subject(), content.body()), request.getVariables());
        if (!missing.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Fill in a value for: " + String.join(", ", missing));
        }
        List<NotificationChannel> channels = request.getChannels().stream().sorted(Comparator.comparing(Enum::ordinal)).toList();

        NotificationBatch batch = batchRepository.save(NotificationBatch.builder()
                .templateId(content.template() == null ? null : content.template().getId())
                .templateCode(content.template() == null ? null : content.template().getCode())
                .templateName(content.template() == null ? null : content.template().getName())
                // Shared values filled in; per-recipient ones ({{username}}…) stay as placeholders.
                .subject(cap(TemplateRenderer.render(content.subject(), values(null, request.getVariables())), 200))
                .channels(channels.stream().map(Enum::name).collect(Collectors.joining(",")))
                .audience(audience.label())
                .recipientCount(audience.users().size())
                .sentBy(actor)
                .build());

        LocalDateTime now = LocalDateTime.now();
        List<Notification> rows = new ArrayList<>();
        for (User user : audience.users()) {
            Map<String, String> values = values(user, request.getVariables());
            String subject = cap(TemplateRenderer.render(content.subject(), values), 300);
            String body = TemplateRenderer.render(content.body(), values);
            for (NotificationChannel channel : channels) {
                boolean noEmail = channel == NotificationChannel.EMAIL && blank(user.getEmail());
                rows.add(Notification.builder()
                        .batchId(batch.getId())
                        .recipientId(user.getId())
                        .recipientUsername(user.getUsername())
                        .recipientEmail(blank(user.getEmail()) ? null : user.getEmail())
                        .channel(channel)
                        .status(channel == NotificationChannel.IN_APP ? NotificationStatus.SENT
                                : noEmail ? NotificationStatus.FAILED : NotificationStatus.PENDING)
                        .attempts(channel == NotificationChannel.IN_APP ? 1 : 0)
                        .sentAt(channel == NotificationChannel.IN_APP ? now : null)
                        .errorMessage(noEmail ? "User has no email address" : null)
                        .templateId(batch.getTemplateId())
                        .templateCode(batch.getTemplateCode())
                        .subject(subject)
                        .body(body)
                        .sentBy(actor)
                        .build());
            }
        }
        rows = notificationRepository.saveAll(rows);

        List<NotificationEvent> timeline = new ArrayList<>();
        List<Long> queued = new ArrayList<>();
        for (Notification n : rows) {
            if (n.getChannel() == NotificationChannel.IN_APP) {
                timeline.add(event(n.getId(), NotificationEventType.SENT, "Delivered to the in-app inbox", actor));
            } else if (n.getStatus() == NotificationStatus.FAILED) {
                timeline.add(event(n.getId(), NotificationEventType.FAILED, n.getErrorMessage(), actor));
            } else {
                timeline.add(event(n.getId(), NotificationEventType.CREATED, "Queued for " + n.getRecipientEmail(), actor));
                queued.add(n.getId());
            }
        }
        eventRepository.saveAll(timeline);
        if (!queued.isEmpty()) {
            // Picked up by NotificationMailer after this transaction commits.
            events.publishEvent(new NotificationMailer.EmailsQueued(queued));
        }
        return toBatch(batch, countsFor(List.of(batch.getId())).get(batch.getId()));
    }

    /**
     * A system message to specific accounts' in-app inboxes (review decisions
     * and the like). Unknown and disabled accounts are skipped; nothing is
     * sent if none remain. "{{" is broken up so user-typed text (a subtitle
     * label) can't be taken for a template variable. Runs in its own
     * transaction, so a failure here can't roll back the caller's work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyInApp(Collection<String> usernames, String subject, String body, String actor) {
        List<Long> ids = usernames.stream().filter(Objects::nonNull).distinct()
                .map(userRepository::findByUsername).flatMap(Optional::stream)
                .filter(User::isEnabled).map(User::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        SendRequest request = new SendRequest();
        request.setSubject(cap(subject.replace("{{", "{ {"), 200));
        request.setBody(body.replace("{{", "{ {"));
        request.setChannels(new LinkedHashSet<>(List.of(NotificationChannel.IN_APP)));
        request.setUserIds(ids);
        send(request, actor);
    }

    /** Same, to every enabled ADMIN account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyAdmins(String subject, String body, String actor) {
        notifyInApp(userRepository.findAll((root, q, cb) -> cb.equal(root.get("role"), Role.ADMIN)).stream()
                .map(User::getUsername).toList(), subject, body, actor);
    }

    private Content content(SendRequest r, boolean strict) {
        NotificationTemplate template = r.getTemplateId() == null ? null : templateService.find(r.getTemplateId());
        String subject = !blank(r.getSubject()) ? r.getSubject().trim() : template == null ? null : template.getSubject();
        String body = !blank(r.getBody()) ? r.getBody().strip() : template == null ? null : template.getBody();
        if (strict && (subject == null || body == null)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Write a subject and message, or choose a template");
        }
        return new Content(template, subject, body);
    }

    private Audience audience(SendRequest r) {
        List<User> users;
        String label;
        int disabled = 0;
        if (r.isAllUsers()) {
            users = userRepository.findAll(Sort.by("username"));
            label = "All users";
        } else if (r.getRole() != null) {
            users = userRepository.findAll((root, q, cb) -> cb.equal(root.get("role"), r.getRole()), Sort.by("username"));
            label = r.getRole() == Role.ADMIN ? "All admins" : "All users with role USER";
        } else if (r.getUserIds() != null && !r.getUserIds().isEmpty()) {
            Set<Long> ids = new LinkedHashSet<>(r.getUserIds());
            Map<Long, User> found = userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
            List<Long> unknown = ids.stream().filter(id -> !found.containsKey(id)).toList();
            if (!unknown.isEmpty()) {
                throw new AppException(HttpStatus.NOT_FOUND, "Users not found: " + unknown.stream().map(String::valueOf).collect(Collectors.joining(", ")));
            }
            users = ids.stream().map(found::get).toList();
            label = "";
        } else {
            return new Audience(List.of(), "Nobody", 0);
        }
        List<User> enabled = users.stream().filter(User::isEnabled).toList();
        disabled = users.size() - enabled.size();
        if (r.isAllUsers() || r.getRole() != null) {
            // Already implies "enabled accounts"; nothing to add.
        } else if (enabled.size() == 1 && disabled == 0) {
            label = enabled.get(0).getUsername();
        } else {
            label = enabled.size() + " selected user" + (enabled.size() == 1 ? "" : "s") + (disabled > 0 ? " (" + disabled + " disabled skipped)" : "");
        }
        if (enabled.size() > MAX_RECIPIENTS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A single send can reach at most " + MAX_RECIPIENTS + " users");
        }
        return new Audience(enabled, label, disabled);
    }

    private Map<String, String> values(User user, Map<String, String> custom) {
        Map<String, String> values = new HashMap<>();
        if (custom != null) {
            custom.forEach((k, v) -> {
                if (k != null && v != null) values.put(k.trim(), cap(v, 1000));
            });
        }
        // Built-ins always win over a same-named custom value.
        values.put("appName", settings.general().siteName());
        values.put("appUrl", settings.publicUrl());
        values.put("date", LocalDate.now().format(DATE));
        if (user != null) {
            values.put("username", user.getUsername());
            values.put("email", user.getEmail() == null ? "" : user.getEmail());
            values.put("role", user.getRole().name().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    // ── Notifications (log) ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(NotificationFilter f) {
        List<Specification<Notification>> c = new ArrayList<>();
        if (!blank(f.getSearch())) {
            String p = likePattern(f.getSearch());
            c.add((r, q, cb) -> cb.or(cb.like(cb.lower(r.get("subject")), p, '\\'), cb.like(cb.lower(r.get("recipientUsername")), p, '\\'),
                    cb.like(cb.lower(r.get("recipientEmail")), p, '\\')));
        }
        if (f.getRecipientId() != null) c.add((r, q, cb) -> cb.equal(r.get("recipientId"), f.getRecipientId()));
        if (f.getChannel() != null) c.add((r, q, cb) -> cb.equal(r.get("channel"), f.getChannel()));
        if (f.getStatus() != null) c.add((r, q, cb) -> cb.equal(r.get("status"), f.getStatus()));
        if (f.getBatchId() != null) c.add((r, q, cb) -> cb.equal(r.get("batchId"), f.getBatchId()));
        if (f.getTemplateId() != null) c.add((r, q, cb) -> cb.equal(r.get("templateId"), f.getTemplateId()));
        if (f.getRead() != null) {
            c.add((r, q, cb) -> cb.equal(r.get("channel"), NotificationChannel.IN_APP));
            c.add((r, q, cb) -> f.getRead() ? cb.isNotNull(r.get("readAt")) : cb.isNull(r.get("readAt")));
        }
        if (f.getFrom() != null) c.add((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("createdAt"), f.getFrom().atStartOfDay()));
        if (f.getTo() != null) c.add((r, q, cb) -> cb.lessThan(r.get("createdAt"), f.getTo().plusDays(1).atStartOfDay()));
        String sortBy = SORTABLE.contains(f.getSortBy()) ? f.getSortBy() : "createdAt";
        return PageResponse.of(notificationRepository.findAll(Specification.allOf(c),
                PageableUtils.of(f.getPage(), Math.min(Math.max(f.getSize(), 1), 100), sortBy, f.getSortOrder())).map(n -> toResponse(n, false, null)));
    }

    @Transactional(readOnly = true)
    public NotificationResponse get(Long id) {
        Notification n = find(id);
        return toResponse(n, true, eventRepository.findByNotificationIdOrderByIdAsc(id));
    }

    /** Retry a failed email (e.g. after fixing SMTP settings or the user's address). */
    @Transactional
    public NotificationResponse resend(Long id, String actor) {
        Notification n = find(id);
        if (n.getChannel() != NotificationChannel.EMAIL) {
            throw new AppException(HttpStatus.CONFLICT, "Only email notifications can be resent");
        }
        if (n.getStatus() != NotificationStatus.FAILED) {
            throw new AppException(HttpStatus.CONFLICT, "Only failed emails can be resent (this one is " + n.getStatus().name().toLowerCase(Locale.ROOT) + ")");
        }
        // Pick up an address added or changed since the first attempt.
        if (n.getRecipientId() != null) {
            userRepository.findById(n.getRecipientId()).ifPresent(u -> n.setRecipientEmail(blank(u.getEmail()) ? null : u.getEmail()));
        }
        if (blank(n.getRecipientEmail())) {
            throw new AppException(HttpStatus.CONFLICT, n.getRecipientUsername() + " has no email address — add one first");
        }
        n.setStatus(NotificationStatus.PENDING);
        n.setErrorMessage(null);
        notificationRepository.save(n);
        eventRepository.save(event(id, NotificationEventType.RETRIED, "Resend to " + n.getRecipientEmail(), actor));
        events.publishEvent(new NotificationMailer.EmailsQueued(List.of(id)));
        return toResponse(n, true, eventRepository.findByNotificationIdOrderByIdAsc(id));
    }

    // ── Notification History (sends) ──────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<BatchResponse> history(BatchFilter f) {
        List<Specification<NotificationBatch>> c = new ArrayList<>();
        if (!blank(f.getSearch())) {
            String p = likePattern(f.getSearch());
            c.add((r, q, cb) -> cb.or(cb.like(cb.lower(r.get("subject")), p, '\\'), cb.like(cb.lower(r.get("templateName")), p, '\\'),
                    cb.like(cb.lower(r.get("templateCode")), p, '\\'), cb.like(cb.lower(r.get("sentBy")), p, '\\')));
        }
        if (f.getTemplateId() != null) c.add((r, q, cb) -> cb.equal(r.get("templateId"), f.getTemplateId()));
        if (!blank(f.getSentBy())) c.add((r, q, cb) -> cb.equal(r.get("sentBy"), f.getSentBy().trim()));
        if (f.getFrom() != null) c.add((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("createdAt"), f.getFrom().atStartOfDay()));
        if (f.getTo() != null) c.add((r, q, cb) -> cb.lessThan(r.get("createdAt"), f.getTo().plusDays(1).atStartOfDay()));
        String sortBy = BATCH_SORTABLE.contains(f.getSortBy()) ? f.getSortBy() : "createdAt";
        Page<NotificationBatch> page = batchRepository.findAll(Specification.allOf(c),
                PageableUtils.of(f.getPage(), Math.min(Math.max(f.getSize(), 1), 100), sortBy, f.getSortOrder()));
        Map<Long, NotificationRepository.BatchCounts> counts = countsFor(page.getContent().stream().map(NotificationBatch::getId).toList());
        return PageResponse.of(page.map(b -> toBatch(b, counts.get(b.getId()))));
    }

    @Transactional(readOnly = true)
    public BatchResponse batch(Long id) {
        NotificationBatch b = batchRepository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Send not found with id: " + id));
        return toBatch(b, countsFor(List.of(id)).get(id));
    }

    // ── Inbox (the signed-in user's in-app notifications) ─────────────────

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> inbox(String username, boolean unreadOnly, int page, int size) {
        Long userId = userId(username);
        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50), Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Notification> result = unreadOnly
                ? notificationRepository.findByRecipientIdAndChannelAndReadAtIsNull(userId, NotificationChannel.IN_APP, pageable)
                : notificationRepository.findByRecipientIdAndChannel(userId, NotificationChannel.IN_APP, pageable);
        return PageResponse.of(result.map(n -> toResponse(n, true, null)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(String username) {
        return notificationRepository.countByRecipientIdAndChannelAndReadAtIsNull(userId(username), NotificationChannel.IN_APP);
    }

    @Transactional
    public NotificationResponse markRead(Long id, String username) {
        Long userId = userId(username);
        Notification n = notificationRepository.findById(id)
                .filter(x -> userId.equals(x.getRecipientId()) && x.getChannel() == NotificationChannel.IN_APP)
                // Someone else's notification is reported as missing, not forbidden.
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Notification not found with id: " + id));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            notificationRepository.save(n);
            eventRepository.save(event(id, NotificationEventType.READ, "Opened in the inbox", username));
        }
        return toResponse(n, true, null);
    }

    @Transactional
    public int markAllRead(String username) {
        List<Long> ids = notificationRepository.unreadIds(userId(username));
        if (ids.isEmpty()) {
            return 0;
        }
        int updated = notificationRepository.markRead(ids, LocalDateTime.now());
        eventRepository.saveAll(ids.stream().map(id -> event(id, NotificationEventType.READ, "Marked all as read", username)).toList());
        return updated;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Long userId(String username) {
        return userRepository.findByUsername(username).map(User::getId)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Account not found"));
    }

    private Notification find(Long id) {
        return notificationRepository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Notification not found with id: " + id));
    }

    private Map<Long, NotificationRepository.BatchCounts> countsFor(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return notificationRepository.countsByBatch(batchIds).stream()
                .collect(Collectors.toMap(NotificationRepository.BatchCounts::getBatchId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static NotificationEvent event(Long notificationId, NotificationEventType type, String detail, String actor) {
        return NotificationEvent.builder().notificationId(notificationId).type(type).detail(detail).actor(actor).build();
    }

    private static NotificationResponse toResponse(Notification n, boolean fullBody, List<NotificationEvent> timeline) {
        boolean truncated = !fullBody && n.getBody().length() > LIST_BODY_PREVIEW;
        String body = truncated ? n.getBody().substring(0, LIST_BODY_PREVIEW).stripTrailing() + "…" : n.getBody();
        return new NotificationResponse(n.getId(), n.getBatchId(), n.getRecipientId(), n.getRecipientUsername(), n.getRecipientEmail(),
                n.getChannel(), n.getStatus(), n.getTemplateId(), n.getTemplateCode(), n.getSubject(), body, truncated, n.getAttempts(),
                n.getErrorMessage(), n.getSentBy(), n.getCreatedAt(), n.getSentAt(), n.getReadAt(),
                timeline == null ? null : timeline.stream().map(e -> new EventDto(e.getId(), e.getType(), e.getDetail(), e.getActor(), e.getCreatedAt())).toList());
    }

    private static BatchResponse toBatch(NotificationBatch b, NotificationRepository.BatchCounts c) {
        List<NotificationChannel> channels = Arrays.stream(b.getChannels().split(",")).filter(s -> !s.isBlank()).map(NotificationChannel::valueOf).toList();
        return new BatchResponse(b.getId(), b.getTemplateId(), b.getTemplateCode(), b.getTemplateName(), b.getSubject(), channels, b.getAudience(),
                b.getRecipientCount(), b.getSentBy(), b.getCreatedAt(),
                c == null ? 0 : c.getTotal(), c == null ? 0 : c.getSent(), c == null ? 0 : c.getFailed(), c == null ? 0 : c.getPending(),
                c == null ? 0 : c.getRead(), c == null ? 0 : c.getInApp());
    }

    static String likePattern(String search) {
        String escaped = search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
