package com.example.videolingo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One granted (module, action) pair for a CustomRole. `module` is a plain
// string matching the URL path segment right after /api/admin/ (e.g.
// "products", "sales-orders") — deliberately not an enum, so the frontend's
// module catalog can grow without a backend change. See
// PermissionAuthorizationManager for how a request's module/action are
// derived and checked against these rows.
@Entity
@Table(name = "role_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "custom_role_id", nullable = false)
    private Long customRoleId;

    @Column(nullable = false)
    private String module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionAction action;
}
