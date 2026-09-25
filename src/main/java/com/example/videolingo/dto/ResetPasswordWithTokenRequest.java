package com.example.videolingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// Self-service reset from an emailed link — distinct from ResetPasswordRequest,
// which is an admin setting another user's password and carries no token.
@Data
public class ResetPasswordWithTokenRequest {

    @NotBlank
    private String token;

    @NotBlank
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String newPassword;
}
