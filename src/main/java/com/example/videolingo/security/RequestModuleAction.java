package com.example.videolingo.security;

import com.example.videolingo.entity.PermissionAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;

import java.util.Set;

// Shared "what module/action does this /api/admin/** request represent"
// vocabulary used by PermissionAuthorizationManager.
public final class RequestModuleAction {

    private static final Set<String> APPROVAL_KEYWORDS = Set.of(
            "approve", "reject", "post", "dispose", "cancel", "reverse",
            "close", "reopen", "ship", "complete", "receive", "void", "checkout"
    );
    private static final Set<String> APPROVAL_PREFIXES = Set.of("run-", "seed");

    private RequestModuleAction() {
    }

    // "/api/admin/sales-orders/5/approve" -> "sales-orders". Null if the path
    // doesn't have a segment after /api/admin/.
    public static String moduleOf(String requestUri, String contextPath) {
        String path = contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
        String prefix = "/api/admin/";
        if (!path.startsWith(prefix)) return null;
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String module = slash < 0 ? rest : rest.substring(0, slash);
        return module.isBlank() ? null : module;
    }

    public static PermissionAction actionOf(HttpServletRequest request) {
        if (HttpMethod.GET.matches(request.getMethod())) {
            return PermissionAction.READ;
        }
        String path = request.getRequestURI();
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = (lastSlash < 0 ? path : path.substring(lastSlash + 1)).toLowerCase();
        if (APPROVAL_KEYWORDS.contains(lastSegment) || APPROVAL_PREFIXES.stream().anyMatch(lastSegment::startsWith)) {
            return PermissionAction.APPROVE;
        }
        return PermissionAction.WRITE;
    }
}
