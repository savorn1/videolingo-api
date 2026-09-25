package com.example.videolingo.dto;

import com.example.videolingo.entity.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateRoleRequest {

    @NotNull
    private Role role;
}
