package com.example.videolingo.service.impl;

import com.example.videolingo.dto.ChangePasswordRequest;
import com.example.videolingo.dto.CreateUserRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.PermissionGrant;
import com.example.videolingo.dto.ResetPasswordRequest;
import com.example.videolingo.dto.UpdateCustomRoleRequest;
import com.example.videolingo.dto.UpdateProfileRequest;
import com.example.videolingo.dto.UpdateRoleRequest;
import com.example.videolingo.dto.UpdateStatusRequest;
import com.example.videolingo.dto.UpdateUserRequest;
import com.example.videolingo.dto.UserFilterRequest;
import com.example.videolingo.dto.UserResponse;
import com.example.videolingo.entity.CustomRole;
import com.example.videolingo.entity.Role;
import com.example.videolingo.entity.User;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.CustomRoleRepository;
import com.example.videolingo.repository.RefreshTokenRepository;
import com.example.videolingo.repository.RolePermissionRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.service.UserService;
import com.example.videolingo.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CustomRoleRepository customRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(UserFilterRequest filter) {
        List<Specification<User>> conditions = new ArrayList<>();

        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%" + filter.getSearch().trim().toLowerCase() + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)));
        }
        if (filter.getUsername() != null && !filter.getUsername().isBlank()) {
            conditions.add((root, query, cb) ->
                    cb.like(cb.lower(root.get("username")), "%" + filter.getUsername().toLowerCase() + "%"));
        }
        if (filter.getRole() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("role"), filter.getRole()));
        }
        if (filter.getEnabled() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("enabled"), filter.getEnabled()));
        }
        if (filter.getCustomRoleId() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("customRoleId"), filter.getCustomRoleId()));
        }
        Specification<User> spec = Specification.allOf(conditions);
        Pageable pageable = PageableUtils.of(filter.getPage(), filter.getSize(), filter.getSortBy(), filter.getSortOrder());

        Page<User> users = userRepository.findAll(spec, pageable);

        // Batch-resolved rather than looked up per row (N+1 avoidance on a plain FK column).
        Map<Long, String> customRoleNames = customRoleRepository.findAllById(
                users.getContent().stream().map(User::getCustomRoleId).filter(Objects::nonNull).distinct().toList()
        ).stream().collect(Collectors.toMap(CustomRole::getId, CustomRole::getName));

        return PageResponse.of(users.map(u -> toResponse(u,
                u.getCustomRoleId() == null ? null : customRoleNames.get(u.getCustomRoleId()))));
    }

    @Override
    public UserResponse getUser(Long id) {
        return toResponse(findUser(id));
    }

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AppException(HttpStatus.CONFLICT, "Username already taken: " + request.getUsername());
        }
        String email = request.getEmail();
        if (email != null && !email.isBlank() && userRepository.existsByEmail(email)) {
            throw new AppException(HttpStatus.CONFLICT, "Email already in use: " + email);
        }
        if (request.getCustomRoleId() != null) {
            requireCustomRole(request.getCustomRoleId());
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(email == null || email.isBlank() ? null : email)
                .role(request.getRole())
                .enabled(request.isEnabled())
                .customRoleId(request.getCustomRoleId())
                .build();
        userRepository.save(user);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = findUser(id);
        String email = request.getEmail();
        if (email == null || email.isBlank()) {
            user.setEmail(null);
        } else {
            if (userRepository.existsByEmailAndIdNot(email, id)) {
                throw new AppException(HttpStatus.CONFLICT, "Email already in use: " + email);
            }
            user.setEmail(email);
        }
        userRepository.save(user);
        return toResponse(user);
    }

    private CustomRole requireCustomRole(Long customRoleId) {
        return customRoleRepository.findById(customRoleId)
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "Custom role not found with id: " + customRoleId));
    }

    @Override
    @Transactional
    public UserResponse updateCustomRole(Long id, UpdateCustomRoleRequest request, String actingUsername) {
        User user = findUser(id);
        // Same self-escalation guard as updateRole — a USER account with
        // permission to manage other users must not be able to grant itself
        // a more-privileged custom role.
        if (user.getUsername().equals(actingUsername) && !Objects.equals(user.getCustomRoleId(), request.getCustomRoleId())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "You cannot change your own custom role");
        }
        if (request.getCustomRoleId() != null) {
            requireCustomRole(request.getCustomRoleId());
        }
        user.setCustomRoleId(request.getCustomRoleId());
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateRole(Long id, UpdateRoleRequest request, String actingUsername) {
        User user = findUser(id);
        if (user.getUsername().equals(actingUsername) && user.getRole() != request.getRole()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "You cannot change your own role");
        }
        user.setRole(request.getRole());
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateStatus(Long id, UpdateStatusRequest request, String actingUsername) {
        User user = findUser(id);
        if (user.getUsername().equals(actingUsername) && !request.getEnabled()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "You cannot deactivate your own account");
        }
        user.setEnabled(request.getEnabled());
        userRepository.save(user);
        if (!user.isEnabled()) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }
        return toResponse(user);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request, String actingUsername) {
        User user = findUser(id);
        if (user.getUsername().equals(actingUsername)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Use change password to update your own password");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    @Override
    @Transactional
    public void deleteUser(Long id, String actingUsername) {
        User user = findUser(id);
        if (user.getUsername().equals(actingUsername)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "You cannot delete your own account");
        }
        refreshTokenRepository.revokeAllForUser(user.getId());
        userRepository.delete(user);
    }

    @Override
    @Transactional
    public void forceLogout(Long id) {
        findUser(id);
        refreshTokenRepository.revokeAllForUser(id);
    }

    @Override
    @Transactional
    public void bulkForceLogout(List<Long> ids) {
        List<User> users = userRepository.findAllById(ids);
        if (users.size() != new HashSet<>(ids).size()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "One or more users were not found");
        }
        refreshTokenRepository.revokeAllForUsers(ids);
    }

    @Override
    @Transactional
    public UserResponse updateProfile(Long id, UpdateProfileRequest request) {
        User user = findUser(id);
        String email = request.getEmail();
        if (email == null || email.isBlank()) {
            user.setEmail(null);
        } else {
            if (userRepository.existsByEmailAndIdNot(email, id)) {
                throw new AppException(HttpStatus.CONFLICT, "Email already in use: " + email);
            }
            user.setEmail(email);
        }
        userRepository.save(user);
        return toResponse(user);
    }

    @Override
    @Transactional
    public void changePassword(Long id, ChangePasswordRequest request) {
        User user = findUser(id);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found with id: " + id));
    }

    private UserResponse toResponse(User user) {
        UserResponse response = toResponse(user, customRoleNameOf(user.getCustomRoleId()));
        response.setPermissions(effectivePermissionsOf(user));
        return response;
    }

    // ADMIN always has full access (see PermissionAuthorizationManager) and
    // never needs a resolved grant list; a USER with no custom role has none.
    private List<PermissionGrant> effectivePermissionsOf(User user) {
        if (user.getRole() != Role.USER || user.getCustomRoleId() == null) {
            return List.of();
        }
        return rolePermissionRepository.findByCustomRoleId(user.getCustomRoleId()).stream()
                .map(p -> new PermissionGrant(p.getModule(), p.getAction()))
                .toList();
    }

    private UserResponse toResponse(User user, String customRoleName) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole().name())
                .enabled(user.isEnabled())
                .customRoleId(user.getCustomRoleId())
                .customRoleName(customRoleName)
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }

    private String customRoleNameOf(Long customRoleId) {
        return customRoleId == null ? null : customRoleRepository.findById(customRoleId).map(CustomRole::getName).orElse(null);
    }
}
