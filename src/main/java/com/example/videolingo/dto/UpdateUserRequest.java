package com.example.videolingo.dto;

import jakarta.validation.constraints.Email;
import lombok.Data;

// Admin-side general edit — distinct from UpdateRoleRequest/UpdateStatusRequest
// (which stay separate since role/status changes revoke sessions).
@Data
public class UpdateUserRequest {

    @Email
    private String email;
}
