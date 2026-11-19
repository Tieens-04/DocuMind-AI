package com.documind.controller;

import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.auth.LoginRequest;
import com.documind.controller.dto.auth.LogoutRequest;
import com.documind.controller.dto.auth.RefreshTokenRequest;
import com.documind.controller.dto.auth.UserProfileResponse;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.infrastructure.security.ClientIpResolver;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST endpoints for stateless JWT authentication, refresh token rotation, logout revocation, and caller profile.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Stateless JWT login, refresh token rotation, logout blacklist, and profile")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
        summary = "Login with email and password",
        description = "Verifies credentials and returns a short-lived JWT access token plus a rotatable opaque refresh token"
    )
    public ResponseEntity<AuthTokenResponse> login(
        @Valid @RequestBody LoginRequest requestBody,
        HttpServletRequest request
    ) {
        AuthTokenResponse response = authService.login(
            requestBody,
            request.getHeader(HttpHeaders.USER_AGENT),
            ClientIpResolver.resolve(request)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
        summary = "Rotate refresh token",
        description = "Issues a new access/refresh token pair and revokes all active sessions if reuse of a rotated token is detected"
    )
    public ResponseEntity<AuthTokenResponse> refresh(
        @Valid @RequestBody RefreshTokenRequest requestBody,
        HttpServletRequest request
    ) {
        AuthTokenResponse response = authService.refresh(
            requestBody,
            request.getHeader(HttpHeaders.USER_AGENT),
            ClientIpResolver.resolve(request)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Operation(
        summary = "Logout and revoke tokens",
        description = "Blacklists the active access token JTI in Redis and revokes either the supplied refresh token or all active sessions"
    )
    public ResponseEntity<Void> logout(
        @AuthenticationPrincipal CustomUserDetails principal,
        @Valid @RequestBody(required = false) LogoutRequest requestBody,
        HttpServletRequest request
    ) {
        if (principal == null) {
            throw new AuthenticationRequiredException("Authenticated security principal is required");
        }
        authService.logout(principal, requestBody, ClientIpResolver.resolve(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(
        summary = "Get current authenticated user profile",
        description = "Returns the authenticated user's profile and department metadata verified from PostgreSQL"
    )
    public ResponseEntity<UserProfileResponse> me(
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        if (principal == null) {
            throw new AuthenticationRequiredException("Authenticated security principal is required");
        }
        return ResponseEntity.ok(authService.getCurrentUser(principal));
    }
}
