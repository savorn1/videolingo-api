package com.example.videolingo.service;

import com.example.videolingo.dto.ChangePasswordRequest;
import com.example.videolingo.dto.CreateUserRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ResetPasswordRequest;
import com.example.videolingo.dto.UpdateCustomRoleRequest;
import com.example.videolingo.dto.UpdateProfileRequest;
import com.example.videolingo.dto.UpdateRoleRequest;
import com.example.videolingo.dto.UpdateStatusRequest;
import com.example.videolingo.dto.UpdateUserRequest;
import com.example.videolingo.dto.UserFilterRequest;
import com.example.videolingo.dto.UserResponse;

import java.util.List;

public interface UserService {

    PageResponse<UserResponse> listUsers(UserFilterRequest filter);

    UserResponse getUser(Long id);

    UserResponse createUser(CreateUserRequest request);

    UserResponse updateUser(Long id, UpdateUserRequest request);

    UserResponse updateRole(Long id, UpdateRoleRequest request, String actingUsername);

    UserResponse updateCustomRole(Long id, UpdateCustomRoleRequest request, String actingUsername);

    UserResponse updateStatus(Long id, UpdateStatusRequest request, String actingUsername);

    void resetPassword(Long id, ResetPasswordRequest request, String actingUsername);

    void deleteUser(Long id, String actingUsername);

    // Revokes the user's refresh tokens without touching role/status/password —
    // the current access token stays valid until it naturally expires, but the
    // next refresh attempt fails and forces a re-login.
    void forceLogout(Long id);

    void bulkForceLogout(List<Long> ids);

    // Self-service — the current user's own email and password (see ProfileController).
    UserResponse updateProfile(Long id, UpdateProfileRequest request);

    void changePassword(Long id, ChangePasswordRequest request);
}
