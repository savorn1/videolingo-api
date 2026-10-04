package com.example.videolingo.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
