package com.example.videolingo.service.impl;

import com.example.videolingo.dto.CustomRoleFilterRequest;
import com.example.videolingo.dto.CustomRoleRequest;
import com.example.videolingo.dto.CustomRoleResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.PermissionGrant;
import com.example.videolingo.entity.CustomRole;
import com.example.videolingo.entity.RolePermission;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.CustomRoleRepository;
import com.example.videolingo.repository.RolePermissionRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.service.CustomRoleService;
import com.example.videolingo.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomRoleServiceImpl implements CustomRoleService {

    private final CustomRoleRepository customRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomRoleResponse> list(CustomRoleFilterRequest filter) {
        List<Specification<CustomRole>> conditions = new ArrayList<>();
        if (filter.getName() != null && !filter.getName().isBlank()) {
            conditions.add((root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + filter.getName().toLowerCase() + "%"));
        }
        Specification<CustomRole> spec = Specification.allOf(conditions);
        Pageable pageable = PageableUtils.of(filter.getPage(), filter.getSize(), filter.getSortBy(), filter.getSortOrder());

        Page<CustomRole> page = customRoleRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomRoleResponse get(Long id) {
        return toResponse(find(id));
    }

    @Override
    @Transactional
    public CustomRoleResponse create(CustomRoleRequest request) {
        if (customRoleRepository.existsByName(request.getName())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A custom role named '" + request.getName() + "' already exists");
        }
        CustomRole role = CustomRole.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        customRoleRepository.save(role);
        savePermissions(role.getId(), request.getPermissions());
        return toResponse(role);
    }

    @Override
    @Transactional
    public CustomRoleResponse update(Long id, CustomRoleRequest request) {
        CustomRole role = find(id);
        if (customRoleRepository.existsByNameAndIdNot(request.getName(), id)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A custom role named '" + request.getName() + "' already exists");
        }
        role.setName(request.getName());
        role.setDescription(request.getDescription());
        customRoleRepository.save(role);
        rolePermissionRepository.deleteByCustomRoleId(id);
        savePermissions(id, request.getPermissions());
        return toResponse(role);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        CustomRole role = find(id);
        if (userRepository.existsByCustomRoleId(id)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cannot delete a custom role that is still assigned to a user");
        }
        rolePermissionRepository.deleteByCustomRoleId(id);
        customRoleRepository.delete(role);
    }

    private void savePermissions(Long customRoleId, List<PermissionGrant> grants) {
        if (grants == null) return;
        for (PermissionGrant grant : grants) {
            rolePermissionRepository.save(RolePermission.builder()
                    .customRoleId(customRoleId)
                    .module(grant.getModule())
                    .action(grant.getAction())
                    .build());
        }
    }

    private CustomRoleResponse toResponse(CustomRole role) {
        List<PermissionGrant> permissions = rolePermissionRepository.findByCustomRoleId(role.getId()).stream()
                .map(p -> new PermissionGrant(p.getModule(), p.getAction()))
                .toList();
        return CustomRoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(permissions)
                .build();
    }

    private CustomRole find(Long id) {
        return customRoleRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Custom role not found with id: " + id));
    }
}
