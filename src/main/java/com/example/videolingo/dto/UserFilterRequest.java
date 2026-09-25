package com.example.videolingo.dto;

import com.example.videolingo.entity.Role;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class UserFilterRequest {

    // Case-insensitive substring match against username OR email — the list
    // page's search box. `username` stays for callers that filter on it alone.
    private String search;
    private String username;
    private Role role;
    private Boolean enabled;
    private Long customRoleId;

    private String sortBy = "id";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 10;
}
