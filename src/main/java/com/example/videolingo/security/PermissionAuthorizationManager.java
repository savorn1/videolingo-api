package com.example.videolingo.security;

import com.example.videolingo.entity.PermissionAction;
import com.example.videolingo.entity.RolePermission;
import com.example.videolingo.entity.User;
import com.example.videolingo.repository.RolePermissionRepository;
import com.example.videolingo.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// Gates every /api/admin/** request (wired in SecurityConfig). ADMIN always
// passes. A USER-role account passes
// only if their assigned CustomRole (User.customRoleId) grants the
// (module, action) this request resolves to:
//   module = the URL segment right after /api/admin/, e.g.
//            /api/admin/sales-orders/5/approve -> "sales-orders"
//   action = READ for GET; otherwise WRITE, unless the last path segment is
//            one of APPROVAL_KEYWORDS (or starts with one of
//            APPROVAL_PREFIXES), in which case APPROVE.
// This is a heuristic, not a hand-annotated mapping — oddly-named endpoints
// may need APPROVAL_KEYWORDS/APPROVAL_PREFIXES in RequestModuleAction extended.
@Component
@RequiredArgsConstructor
public class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authenticationSupplier, RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();
        if (authentication == null || !authentication.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        List<String> authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        if (authorities.contains("ROLE_ADMIN")) {
            return new AuthorizationDecision(true);
        }
        if (!authorities.contains("ROLE_USER")) {
            return new AuthorizationDecision(false);
        }

        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null || user.getCustomRoleId() == null) {
            return new AuthorizationDecision(false);
        }

        HttpServletRequest request = context.getRequest();
        String module = RequestModuleAction.moduleOf(request.getRequestURI(), request.getContextPath());
        if (module == null) {
            return new AuthorizationDecision(false);
        }
        PermissionAction action = RequestModuleAction.actionOf(request);

        List<RolePermission> grants = rolePermissionRepository.findByCustomRoleId(user.getCustomRoleId());
        boolean granted = grants.stream().anyMatch(g -> g.getModule().equals(module) && g.getAction() == action);
        return new AuthorizationDecision(granted);
    }
}
