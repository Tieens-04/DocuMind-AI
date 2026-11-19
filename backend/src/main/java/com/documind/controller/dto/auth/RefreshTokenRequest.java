package com.documind.controller.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Refresh token payload for {@code POST /api/v1/auth/refresh}.
 */
public record RefreshTokenRequest(
    @NotBlank(message = "Refresh token is required")
    @Size(max = 512, message = "Refresh token must not exceed 512 characters")
    String refreshToken
) {
}
