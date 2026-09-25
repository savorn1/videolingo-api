package com.example.videolingo.entity;

// What a RolePermission grants for one module. GET requests need READ;
// everything else needs WRITE, unless the request's path matches one of
// PermissionAuthorizationManager's approval-style keywords, which need
// APPROVE instead — see that class for the exact derivation.
public enum PermissionAction {
    READ,
    WRITE,
    APPROVE
}
