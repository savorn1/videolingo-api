package com.example.videolingo.audit;

import com.example.videolingo.apikey.ApiKeyAuthenticationToken;
import com.example.videolingo.entity.AuditLog;
import com.example.videolingo.security.RequestModuleAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.time.LocalDateTime;

// Writes an AuditLog row for each request AuditPolicy keeps. Registered
// inside the security chain right after authentication (SecurityConfig), so
// requests the permission check turns away are logged too, as 401/403.
// Not a @Component: Boot would also register it as a plain servlet filter.
public class AuditRequestFilter extends OncePerRequestFilter {

    private static final String LOGIN = "/api/auth/login";
    private static final int LOGIN_BODY_LIMIT = 4096;

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditRequestFilter(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuditPolicy.shouldLog(request.getMethod(), path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        // Only the login body is kept (to name who tried to sign in), and only its username.
        HttpServletRequest req = LOGIN.equals(path) ? new ContentCachingRequestWrapper(request, LOGIN_BODY_LIMIT) : request;
        long started = System.nanoTime();
        try {
            chain.doFilter(req, response);
        } finally {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean signedIn = auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName());
            String detail = null;
            String username = signedIn ? auth.getName() : null;
            if (req instanceof ContentCachingRequestWrapper cached) {
                String tried = loginUsername(cached.getContentAsByteArray());
                if (response.getStatus() < 400) {
                    username = tried;
                } else if (tried != null) {
                    detail = "Sign-in attempt as \"" + tried + "\"";
                }
            }
            auditService.record(AuditLog.builder()
                    .createdAt(LocalDateTime.now())
                    .username(cap(username, 100))
                    .authType(auth instanceof ApiKeyAuthenticationToken ? "api-key" : signedIn || username != null ? "session" : null)
                    .method(request.getMethod().toUpperCase())
                    .path(cap(path, 500))
                    .module(cap(AuditPolicy.moduleOf(path), 60))
                    .action(path.startsWith("/api/admin/") ? RequestModuleAction.actionOf(request).name() : null)
                    .entityId(AuditPolicy.entityIdOf(path))
                    .status(response.getStatus())
                    .durationMs((System.nanoTime() - started) / 1_000_000)
                    .ipAddress(cap(clientIp(request), 64))
                    .userAgent(cap(request.getHeader("User-Agent"), 300))
                    .detail(cap(detail, 300))
                    .build());
        }
    }

    private String loginUsername(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            String name = objectMapper.readTree(body).path("username").asText("");
            return name.isBlank() ? null : name.strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    // Behind the Nuxt proxy the socket address is the proxy's, so prefer the
    // first X-Forwarded-For hop. Informational only — clients can set it.
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static String cap(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
