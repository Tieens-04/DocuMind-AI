package com.documind.controller.dto.admin;

import com.documind.domain.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial update payload for modifying user identity, role, department assignment, or activation state.
 */
public record UpdateUserRequest(
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    String email,

    @Size(max = 150, message = "Full name must not exceed 150 characters")
    String fullName,

    Role role,

    UUID departmentId,

    Boolean isActive
) {
    public UpdateUserRequest {
        email = email != null ? email.trim() : null;
    }
}
