package com.example.videolingo.audit;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.AuditLog;
import com.example.videolingo.repository.AuditLogRepository;
import com.example.videolingo.service.impl.TranscriptServiceImpl;
import com.example.videolingo.util.PageableUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    /** Entries older than this are deleted nightly. */
    static final int RETENTION_DAYS = 365;
    private static final Set<String> SORTABLE = Set.of("createdAt", "username", "module", "method", "status", "durationMs");

    @Data
    @ParameterObject
    public static class AuditFilter {
        private String username;
        private String module;
        private String method;
        // "success" (2xx/3xx), "failed" (4xx/5xx except 401/403) or "denied" (401/403).
        private String outcome;
        private Long entityId;
        // Path contains.
        private String search;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;

        private String sortBy = "createdAt";
        private String sortOrder = "desc";
        private int page = 1;
        private int size = 50;
    }

    public record AuditEntry(Long id, LocalDateTime createdAt, String username, String authType, String method, String path, String module,
                             String action, Long entityId, int status, long durationMs, String ipAddress, String userAgent, String detail) {
    }

    private final AuditLogRepository repository;

    // Off the request thread: the log must never slow down or fail a request.
    @Async
    @Transactional
    public void record(AuditLog entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException e) {
            log.warn("Couldn't write audit log entry for {} {}", entry.getMethod(), entry.getPath(), e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> list(AuditFilter f) {
        List<Specification<AuditLog>> c = new ArrayList<>();
        if (f.getUsername() != null && !f.getUsername().isBlank()) {
            c.add((root, q, cb) -> cb.equal(cb.lower(root.get("username")), f.getUsername().strip().toLowerCase(Locale.ROOT)));
        }
        if (f.getModule() != null && !f.getModule().isBlank()) {
            c.add((root, q, cb) -> cb.equal(root.get("module"), f.getModule().strip()));
        }
        if (f.getMethod() != null && !f.getMethod().isBlank()) {
            c.add((root, q, cb) -> cb.equal(root.get("method"), f.getMethod().strip().toUpperCase(Locale.ROOT)));
        }
        if (f.getEntityId() != null) {
            c.add((root, q, cb) -> cb.equal(root.get("entityId"), f.getEntityId()));
        }
        if (f.getSearch() != null && !f.getSearch().isBlank()) {
            String pattern = "%" + TranscriptServiceImpl.escapeLike(f.getSearch().strip().toLowerCase(Locale.ROOT)) + "%";
            c.add((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("path")), pattern, '\\'), cb.like(cb.lower(root.get("detail")), pattern, '\\')));
        }
        if (f.getOutcome() != null) {
            switch (f.getOutcome()) {
                case "success" -> c.add((root, q, cb) -> cb.lessThan(root.get("status"), 400));
                case "denied" -> c.add((root, q, cb) -> root.get("status").in(401, 403));
                case "failed" -> c.add((root, q, cb) -> cb.and(cb.greaterThanOrEqualTo(root.get("status"), 400), cb.not(root.get("status").in(401, 403))));
                default -> {
                }
            }
        }
        if (f.getFrom() != null) {
            c.add((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), f.getFrom().atStartOfDay()));
        }
        if (f.getTo() != null) {
            c.add((root, q, cb) -> cb.lessThan(root.get("createdAt"), f.getTo().plusDays(1).atStartOfDay()));
        }
        String sortBy = SORTABLE.contains(f.getSortBy()) ? f.getSortBy() : "createdAt";
        int size = Math.max(1, Math.min(f.getSize(), 200));
        return PageResponse.of(repository.findAll(Specification.allOf(c), PageableUtils.of(f.getPage(), size, sortBy, f.getSortOrder()))
                .map(AuditService::toEntry));
    }

    @Transactional(readOnly = true)
    public List<String> modules() {
        return repository.modules();
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        int n = repository.deleteOlderThan(LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (n > 0) {
            log.info("Deleted {} audit log entries older than {} days", n, RETENTION_DAYS);
        }
    }

    private static AuditEntry toEntry(AuditLog a) {
        return new AuditEntry(a.getId(), a.getCreatedAt(), a.getUsername(), a.getAuthType(), a.getMethod(), a.getPath(), a.getModule(),
                a.getAction(), a.getEntityId(), a.getStatus(), a.getDurationMs(), a.getIpAddress(), a.getUserAgent(), a.getDetail());
    }
}
