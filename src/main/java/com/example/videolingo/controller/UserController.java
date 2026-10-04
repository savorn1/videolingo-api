package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CreateUserRequest;
import com.example.videolingo.dto.ForceLogoutRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ResetPasswordRequest;
import com.example.videolingo.dto.UpdateCustomRoleRequest;
import com.example.videolingo.dto.UpdateRoleRequest;
import com.example.videolingo.dto.UpdateStatusRequest;
import com.example.videolingo.dto.UpdateUserRequest;
import com.example.videolingo.dto.UserFilterRequest;
import com.example.videolingo.dto.UserResponse;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// Admin-only user management, kept separate from AuthController (login), which is
// permitAll and unauthenticated.
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<PageResponse<UserResponse>> list(@ModelAttribute UserFilterRequest filter) {
        return ResponseEntity.ok(userService.listUsers(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created", userService.createUser(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.success("User updated", userService.updateUser(id, request)));
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(
            @PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Role updated", userService.updateRole(id, request, requireUsername(authentication))));
    }

    @PutMapping("/{id}/custom-role")
    public ResponseEntity<ApiResponse<UserResponse>> updateCustomRole(
            @PathVariable Long id, @Valid @RequestBody UpdateCustomRoleRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Custom role updated", userService.updateCustomRole(id, request, requireUsername(authentication))));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<UserResponse>> updateStatus(
            @PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Status updated", userService.updateStatus(id, request, requireUsername(authentication))));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request, Authentication authentication) {
        userService.resetPassword(id, request, requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success("Password reset", null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Authentication authentication) {
        userService.deleteUser(id, requireUsername(authentication));
        return ResponseEntity.ok(ApiResponse.success("User deleted", null));
    }

    @PostMapping("/{id}/force-logout")
    public ResponseEntity<ApiResponse<Void>> forceLogout(@PathVariable Long id) {
        userService.forceLogout(id);
        return ResponseEntity.ok(ApiResponse.success("User logged out", null));
    }

    @PostMapping("/force-logout")
    public ResponseEntity<ApiResponse<Void>> bulkForceLogout(@Valid @RequestBody ForceLogoutRequest request) {
        userService.bulkForceLogout(request.getUserIds());
        return ResponseEntity.ok(ApiResponse.success("Users logged out", null));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
