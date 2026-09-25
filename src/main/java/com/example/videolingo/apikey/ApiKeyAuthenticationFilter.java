package com.example.videolingo.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Signs a request in as an API key's owner. The key goes in X-API-Key, or in
// "Authorization: Bearer vl_…" for tools that only speak Bearer tokens. A key
// that's wrong, revoked or expired gets a 401 straight away rather than
// falling through as an anonymous request. Runs before JwtAuthenticationFilter;
// a Bearer value that isn't key-shaped is left for that filter.
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ") && auth.substring(7).startsWith(ApiKeys.PREFIX)) {
                key = auth.substring(7);
            }
        }
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        var owner = apiKeyService.authenticate(key.strip());
        if (owner.isEmpty()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid, revoked or expired API key\"}");
            return;
        }
        var user = owner.get().user();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthenticationToken(user.getUsername(), owner.get().keyId(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
