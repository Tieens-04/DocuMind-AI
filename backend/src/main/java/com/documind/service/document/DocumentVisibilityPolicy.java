package com.documind.service.document;

import com.documind.domain.enums.AccessLevel;

import java.util.Objects;
import java.util.UUID;

/**
 * Single authoritative RBAC visibility policy matching {@code AGENTS.md} §3.1:
 * <ul>
 *     <li>{@code SYSTEM_ADMIN}: full access across departments and classification levels.</li>
 *     <li>{@code DEPARTMENT_MANAGER}: {@code PUBLIC} anywhere, plus all levels within own department.</li>
 *     <li>{@code STANDARD_EMPLOYEE}: {@code PUBLIC} anywhere, plus {@code DEPARTMENT} within own department (never {@code CONFIDENTIAL}).</li>
 * </ul>
 */
public final class DocumentVisibilityPolicy {

    private DocumentVisibilityPolicy() {
    }

    public static boolean canRead(
        CallerContext caller,
        UUID documentDepartmentId,
        AccessLevel documentAccessLevel
    ) {
        if (caller == null || caller.role() == null || documentAccessLevel == null) {
            return false;
        }
        return switch (caller.role()) {
            case SYSTEM_ADMIN -> true;
            case DEPARTMENT_MANAGER -> documentAccessLevel == AccessLevel.PUBLIC
                || Objects.equals(caller.departmentId(), documentDepartmentId);
            case STANDARD_EMPLOYEE -> documentAccessLevel == AccessLevel.PUBLIC
                || (Objects.equals(caller.departmentId(), documentDepartmentId)
                    && documentAccessLevel == AccessLevel.DEPARTMENT);
        };
    }
}
