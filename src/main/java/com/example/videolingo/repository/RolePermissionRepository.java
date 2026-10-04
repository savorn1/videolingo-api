package com.example.videolingo.repository;

import com.example.videolingo.entity.RolePermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    List<RolePermission> findByCustomRoleId(Long customRoleId);

    void deleteByCustomRoleId(Long customRoleId);
}
