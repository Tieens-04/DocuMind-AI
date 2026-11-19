package com.documind.controller.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials payload for {@code POST /api/v1/auth/login}.
 */
public record LoginRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    String email,
    @NotBlank(message = "Password is required")
    @Size(max = 256, message = "Password must not exceed 256 characters")
    String password
) {
}
