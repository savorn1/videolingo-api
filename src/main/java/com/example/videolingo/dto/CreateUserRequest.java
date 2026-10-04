package com.example.videolingo.dto;

import com.example.videolingo.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank
    private String username;

    @NotBlank
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    @Email
    private String email;

    @NotNull
    private Role role = Role.USER;

    private boolean enabled = true;

    // Only meaningful when role == USER — see PermissionAuthorizationManager.
    // Can also be set/changed later via PUT /{id}/custom-role.
    private Long customRoleId;
}
