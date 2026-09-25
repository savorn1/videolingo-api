package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomRoleResponse {

    private Long id;
    private String name;
    private String description;
    private List<PermissionGrant> permissions;
}
