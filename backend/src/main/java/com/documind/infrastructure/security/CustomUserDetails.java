package com.documind.infrastructure.security;

import com.documind.domain.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authenticated enterprise user principal carrying identity, department scope,
 * RBAC role authority, and optional JWT metadata ({@code jti}, {@code expiresAt}).
 */
public final class CustomUserDetails implements UserDetails {

    private final UUID userId;
    private final UUID departmentId;
    private final String email;
    private final String fullName;
    private final Role role;
    private final String tokenJti;
    private final Instant tokenExpiresAt;
    private final List<GrantedAuthority> authorities;

    public CustomUserDetails(
        UUID userId,
        UUID departmentId,
        String email,
        String fullName,
        Role role,
        String tokenJti,
        Instant tokenExpiresAt
    ) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.departmentId = Objects.requireNonNull(departmentId, "departmentId must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.fullName = Objects.requireNonNull(fullName, "fullName must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.tokenJti = tokenJti;
        this.tokenExpiresAt = tokenExpiresAt;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public static CustomUserDetails fromClaims(AuthenticatedTokenClaims claims) {
        Objects.requireNonNull(claims, "claims must not be null");
        return new CustomUserDetails(
            claims.userId(),
            claims.departmentId(),
            claims.email(),
            claims.fullName(),
            claims.role(),
            claims.jti(),
            claims.expiresAt()
        );
    }

    public UUID userId() {
        return userId;
    }

    public UUID departmentId() {
        return departmentId;
    }

    public String email() {
        return email;
    }

    public String fullName() {
        return fullName;
    }

    public Role role() {
        return role;
    }

    public String tokenJti() {
        return tokenJti;
    }

    public Instant tokenExpiresAt() {
        return tokenExpiresAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
