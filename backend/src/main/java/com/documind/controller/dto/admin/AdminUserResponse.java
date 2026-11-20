package com.documind.controller.dto.admin;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative representation of a user account and home department (never exposing password hashes).
 */
public record AdminUserResponse(
    UUID id,
    String email,
    String fullName,
    Role role,
    UUID departmentId,
    String departmentCode,
    String departmentName,
    boolean isActive,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    public static AdminUserResponse fromEntity(UserEntity user) {
        Objects.requireNonNull(user, "user must not be null");
        DepartmentEntity department = user.getDepartment();
        return new AdminUserResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole(),
            user.getDepartmentId(),
            department != null ? department.getCode() : null,
            department != null ? department.getName() : null,
            user.isActive(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }
}
