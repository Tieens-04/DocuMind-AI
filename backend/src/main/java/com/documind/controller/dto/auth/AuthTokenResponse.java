package com.documind.controller.dto.auth;

/**
 * Access and rotated refresh token bundle returned by {@code /login} and {@code /refresh}.
 */
public record AuthTokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    long refreshExpiresIn,
    UserProfileResponse user
) {
}
