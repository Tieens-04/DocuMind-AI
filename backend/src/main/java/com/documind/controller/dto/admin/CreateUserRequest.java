package com.documind.controller.dto.admin;

import com.documind.domain.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request payload for provisioning a new enterprise user account.
 */
public record CreateUserRequest(
    @NotBlank(message = "Email must not be blank")
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    String email,

    @NotBlank(message = "Full name must not be blank")
    @Size(max = 150, message = "Full name must not exceed 150 characters")
    String fullName,

    @NotBlank(message = "Password must not be blank")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters (max 72 UTF-8 bytes)")
    String password,

    @NotNull(message = "Role must not be null")
    Role role,

    @NotNull(message = "Department ID must not be null")
    UUID departmentId,

    Boolean isActive
) {
    public CreateUserRequest {
        email = email != null ? email.trim() : null;
    }

    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email
            + ", fullName=" + fullName
            + ", password=[REDACTED]"
            + ", role=" + role
            + ", departmentId=" + departmentId
            + ", isActive=" + isActive + "]";
    }
}
