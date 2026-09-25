package com.example.videolingo.service;

import com.example.videolingo.dto.CustomRoleFilterRequest;
import com.example.videolingo.dto.CustomRoleRequest;
import com.example.videolingo.dto.CustomRoleResponse;
import com.example.videolingo.dto.PageResponse;

public interface CustomRoleService {

    PageResponse<CustomRoleResponse> list(CustomRoleFilterRequest filter);

    CustomRoleResponse get(Long id);

    CustomRoleResponse create(CustomRoleRequest request);

    CustomRoleResponse update(Long id, CustomRoleRequest request);

    void delete(Long id);
}
