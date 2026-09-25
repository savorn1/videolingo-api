package com.example.videolingo.audit;

import java.util.Locale;
import java.util.Set;

// Which requests the audit log keeps, and how a path is described. Pure and
// static so it's unit-tested.
public final class AuditPolicy {

    private static final Set<String> AUTH_EVENTS = Set.of(
            "/api/auth/login", "/api/auth/logout", "/api/auth/forgot-password", "/api/auth/reset-password");

    private AuditPolicy() {
    }

    /**
     * Change-making requests only: every non-GET under /api/admin/, sign-in
     * and password events, profile edits and file uploads. Token refreshes
     * and inbox read-marks are left out — they're frequent and say nothing
     * about who changed what.
     */
    public static boolean shouldLog(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) {
            return false;
        }
        if (path.startsWith("/api/admin/")) {
            return true;
        }
        if (AUTH_EVENTS.contains(path)) {
            return true;
        }
        if (path.startsWith("/api/users/me/notifications")) {
            return false;
        }
        return path.equals("/api/users/me") || path.equals("/api/users/me/password") || path.startsWith("/api/files/");
    }

    /** "/api/admin/videos/12/tags" → "videos"; "/api/auth/login" → "auth"; "/api/users/me" → "profile". */
    public static String moduleOf(String path) {
        if (path.startsWith("/api/admin/")) {
            String rest = path.substring("/api/admin/".length());
            int slash = rest.indexOf('/');
            String module = slash < 0 ? rest : rest.substring(0, slash);
            return module.isBlank() ? "admin" : module;
        }
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/users/me")) {
            return "profile";
        }
        if (path.startsWith("/api/files/")) {
            return "files";
        }
        return "other";
    }

    /** The first all-digit path segment after the module, or null: "/api/admin/videos/12/tags/3" → 12. */
    public static Long entityIdOf(String path) {
        String[] parts = path.split("/");
        // "", "api", "admin", module, …
        int start = path.startsWith("/api/admin/") ? 4 : 3;
        for (int i = start; i < parts.length; i++) {
            if (!parts[i].isEmpty() && parts[i].length() <= 18 && parts[i].chars().allMatch(Character::isDigit)) {
                return Long.parseLong(parts[i]);
            }
        }
        return null;
    }
}
