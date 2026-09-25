package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;
    private String username;
    private String email;
    private String role;
    private boolean enabled;
    private Long customRoleId;
    private String customRoleName;
    // Null for accounts created before these columns existed / that have
    // never signed in.
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    // This user's effective permission grants — empty for ADMIN (who always
    // has full access, see PermissionAuthorizationManager) and for a USER
    // with no custom role assigned. Only populated on the single-user
    // response path (getUser/create/update/etc), not the batch list path —
    // the admin Users table doesn't need every row's full grant set.
    private List<PermissionGrant> permissions;
}
