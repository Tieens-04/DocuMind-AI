package com.documind.infrastructure.security;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.InvalidTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String STRONG_TEST_SECRET = "test-only-hmac-sha256-jwt-secret-key-at-least-32-bytes-long";
    private static final String OTHER_STRONG_SECRET = "another-totally-different-hmac-sha256-secret-key-32-bytes";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void generatesAndParsesAccessTokenWithAllCanonicalClaims() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        JwtProperties properties = new JwtProperties(
            STRONG_TEST_SECRET,
            "documind-ai",
            Duration.ofMinutes(15),
            Duration.ofDays(7)
        );
        JwtService jwtService = new JwtService(properties, new MockEnvironment(), clock);

        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID deptId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UserEntity user = buildUser(userId, deptId, "manager.hr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER);

        JwtService.IssuedAccessToken issued = jwtService.generateAccessToken(user);

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.jti()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isEqualTo(900L);
        assertThat(issued.expiresAt().toInstant()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));

        AuthenticatedTokenClaims claims = jwtService.parseAndValidateAccessToken(issued.token());
        assertThat(claims.jti()).isEqualTo(issued.jti());
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.departmentId()).isEqualTo(deptId);
        assertThat(claims.email()).isEqualTo("manager.hr@documind.ai");
        assertThat(claims.fullName()).isEqualTo("HR Manager");
        assertThat(claims.role()).isEqualTo(Role.DEPARTMENT_MANAGER);
        assertThat(claims.issuedAt()).isEqualTo(FIXED_NOW);
        assertThat(claims.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void rejectsExpiredTokenWithTokenExpiredErrorCode() {
        JwtProperties properties = new JwtProperties(
            STRONG_TEST_SECRET,
            "documind-ai",
            Duration.ofMinutes(15),
            Duration.ofDays(7)
        );
        JwtService issuerService = new JwtService(
            properties,
            new MockEnvironment(),
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
        JwtService verifierAfterExpiry = new JwtService(
            properties,
            new MockEnvironment(),
            Clock.fixed(FIXED_NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC)
        );

        UserEntity user = buildUser(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "employee@documind.ai",
            "Employee",
            Role.STANDARD_EMPLOYEE
        );
        String token = issuerService.generateAccessToken(user).token();

        assertThatThrownBy(() -> verifierAfterExpiry.parseAndValidateAccessToken(token))
            .isInstanceOf(InvalidTokenException.class)
            .satisfies(ex -> assertThat(((InvalidTokenException) ex).errorCode())
                .isEqualTo(InvalidTokenException.CODE_TOKEN_EXPIRED));
    }

    @Test
    void rejectsTamperedWrongSecretWrongIssuerMalformedAndUnsignedTokens() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        JwtProperties properties = new JwtProperties(
            STRONG_TEST_SECRET,
            "documind-ai",
            Duration.ofMinutes(15),
            Duration.ofDays(7)
        );
        JwtService jwtService = new JwtService(properties, new MockEnvironment(), clock);
        UserEntity user = buildUser(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "admin@documind.ai",
            "System Admin",
            Role.SYSTEM_ADMIN
        );
        String validToken = jwtService.generateAccessToken(user).token();

        // Tampered payload
        String[] parts = validToken.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertInvalidToken(jwtService, tamperedToken);
        assertInvalidToken(jwtService, null);
        assertInvalidToken(jwtService, "   ");
        assertInvalidToken(jwtService, "not.a.jwt");

        // Unsigned alg=none token
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        assertInvalidToken(jwtService, noneHeader + "." + parts[1] + ".");

        // Wrong secret
        JwtService otherSecretService = new JwtService(
            new JwtProperties(OTHER_STRONG_SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
            new MockEnvironment(),
            clock
        );
        assertInvalidToken(otherSecretService, validToken);

        // Wrong issuer
        JwtService otherIssuerService = new JwtService(
            new JwtProperties(STRONG_TEST_SECRET, "other-issuer", Duration.ofMinutes(15), Duration.ofDays(7)),
            new MockEnvironment(),
            clock
        );
        assertInvalidToken(otherIssuerService, validToken);
    }

    @Test
    void rejectsWeakSecretAndAllowsDefaultDevSecretOnlyInDevLocalOrTestProfiles() {
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        assertThatThrownBy(() -> new JwtService(
            new JwtProperties("short-secret", "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
            devEnv
        )).isInstanceOf(IllegalStateException.class);

        // Default dev secret is permitted strictly in dev, local, or test (without prod)
        for (String allowedProfile : new String[]{"dev", "local", "test"}) {
            MockEnvironment allowedEnv = new MockEnvironment();
            allowedEnv.setActiveProfiles(allowedProfile);
            JwtService allowedService = new JwtService(
                new JwtProperties(JwtProperties.DEFAULT_DEV_SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
                allowedEnv
            );
            assertThat(allowedService.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        }

        // Default dev secret MUST be rejected on prod, staging, production, dev+prod, unprofiled, and null env
        for (String[] disallowedProfiles : new String[][]{
            {"prod"},
            {"dev", "prod"},
            {"staging"},
            {"production"},
            {}
        }) {
            MockEnvironment disallowedEnv = new MockEnvironment();
            if (disallowedProfiles.length > 0) {
                disallowedEnv.setActiveProfiles(disallowedProfiles);
            }
            assertThatThrownBy(() -> new JwtService(
                new JwtProperties(JwtProperties.DEFAULT_DEV_SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
                disallowedEnv
            )).isInstanceOf(IllegalStateException.class);
        }

        assertThatThrownBy(() -> new JwtService(
            new JwtProperties(JwtProperties.DEFAULT_DEV_SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
            null
        )).isInstanceOf(IllegalStateException.class);

        // Strong unique secret works on prod, staging, and unprofiled environments
        for (String[] nonDevProfiles : new String[][]{{"prod"}, {"staging"}, {}}) {
            MockEnvironment env = new MockEnvironment();
            if (nonDevProfiles.length > 0) {
                env.setActiveProfiles(nonDevProfiles);
            }
            JwtService valid = new JwtService(
                new JwtProperties(STRONG_TEST_SECRET, "documind-ai", Duration.ofMinutes(15), Duration.ofDays(7)),
                env
            );
            assertThat(valid.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        }
    }

    private static void assertInvalidToken(JwtService jwtService, String token) {
        assertThatThrownBy(() -> jwtService.parseAndValidateAccessToken(token))
            .isInstanceOf(InvalidTokenException.class)
            .satisfies(ex -> assertThat(((InvalidTokenException) ex).errorCode())
                .isEqualTo(InvalidTokenException.CODE_INVALID_TOKEN));
    }

    private static UserEntity buildUser(UUID userId, UUID deptId, String email, String fullName, Role role) {
        DepartmentEntity department = new DepartmentEntity();
        department.setId(deptId);
        department.setCode("HR");
        department.setName("Human Resources");

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setDepartment(department);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setIsActive(true);
        return user;
    }
}
