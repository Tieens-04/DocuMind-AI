package com.documind.controller.dto.auth;

import jakarta.validation.constraints.Size;

/**
 * Optional logout payload for revoking a single refresh token session.
 */
public record LogoutRequest(
    @Size(max = 512, message = "Refresh token must not exceed 512 characters")
    String refreshToken
) {
}
