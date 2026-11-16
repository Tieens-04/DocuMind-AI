package com.documind.service.document;

import com.documind.domain.enums.Role;

import java.util.UUID;

/**
 * Resolved caller identity, department scope, and role for document operations.
 */
public record CallerContext(
    UUID userId,
    UUID departmentId,
    Role role,
    String ipAddress
) {

    public boolean isSystemAdmin() {
        return role == Role.SYSTEM_ADMIN;
    }

    public boolean canManageDocuments() {
        return role == Role.SYSTEM_ADMIN || role == Role.DEPARTMENT_MANAGER;
    }
}
