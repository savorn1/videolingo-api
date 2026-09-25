package com.example.videolingo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CustomRoleRequest {

    @NotBlank
    private String name;

    private String description;

    // The role's full grant set — a save replaces every existing grant with
    // this list (simplest correct semantics).
    @Valid
    private List<PermissionGrant> permissions = new ArrayList<>();
}
