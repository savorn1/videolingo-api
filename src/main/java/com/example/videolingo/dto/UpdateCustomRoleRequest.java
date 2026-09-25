package com.example.videolingo.dto;

import lombok.Data;

@Data
public class UpdateCustomRoleRequest {

    // Null clears the assignment (a USER account with no custom role has no
    // permissions under /api/admin/** — see PermissionAuthorizationManager).
    private Long customRoleId;
}
