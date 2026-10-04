package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.ChangePasswordRequest;
import com.example.videolingo.dto.UpdateProfileRequest;
import com.example.videolingo.dto.UserResponse;
import com.example.videolingo.security.CurrentUserResolver;
import com.example.videolingo.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Self-service profile endpoints for the currently authenticated user (USER or
// ADMIN), kept separate from UserController (admin-only, class-level
// @PreAuthorize("hasAnyRole('ADMIN','USER')")) so these are reachable by any
// authenticated user without needing a per-method override there.
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.success(userService.getUser(currentUserResolver.requireUserId(authentication))));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "Profile updated",
                userService.updateProfile(currentUserResolver.requireUserId(authentication), request)));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changeMyPassword(
            @Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        userService.changePassword(currentUserResolver.requireUserId(authentication), request);
        return ResponseEntity.ok(ApiResponse.success("Password changed", null));
    }
}
