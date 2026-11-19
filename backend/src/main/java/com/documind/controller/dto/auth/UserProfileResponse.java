package com.documind.controller.dto.auth;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated enterprise user profile returned by auth endpoints and {@code GET /api/v1/auth/me}.
 */
public record UserProfileResponse(
    UUID id,
    String email,
    String fullName,
    Role role,
    UUID departmentId,
    String departmentCode,
    String departmentName,
    boolean isActive,
    OffsetDateTime createdAt
) {

    public static UserProfileResponse fromEntity(UserEntity user) {
        Objects.requireNonNull(user, "user must not be null");
        DepartmentEntity dept = user.getDepartment();
        return new UserProfileResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole(),
            user.getDepartmentId(),
            dept != null ? dept.getCode() : null,
            dept != null ? dept.getName() : null,
            user.isActive(),
            user.getCreatedAt()
        );
    }
}
