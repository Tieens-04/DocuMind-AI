package com.documind.service.auth;

import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.auth.LoginRequest;
import com.documind.controller.dto.auth.LogoutRequest;
import com.documind.controller.dto.auth.RefreshTokenRequest;
import com.documind.controller.dto.auth.UserProfileResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.RefreshTokenEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.Role;
import com.documind.exception.AccountDisabledException;
import com.documind.exception.InvalidCredentialsException;
import com.documind.exception.InvalidTokenException;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.security.JwtProperties;
import com.documind.infrastructure.security.JwtService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.RefreshTokenRepository;
import com.documind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private AuditLogRepository auditLogRepository;
    private RecordingTransactionManager transactionManager;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private TokenBlacklistService tokenBlacklistService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        transactionManager = new RecordingTransactionManager();
        passwordEncoder = spy(new BCryptPasswordEncoder(4));
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        jwtService = new JwtService(
            new JwtProperties(
                "auth-service-test-hmac-sha256-jwt-secret-key-min-32b",
                "documind-ai",
                Duration.ofMinutes(15),
                Duration.ofDays(7)
            ),
            new MockEnvironment(),
            clock
        );
        tokenBlacklistService = mock(TokenBlacklistService.class);
        authService = new AuthService(
            userRepository,
            refreshTokenRepository,
            auditLogRepository,
            transactionManager,
            passwordEncoder,
            jwtService,
            tokenBlacklistService,
            clock
        );
    }

    @Test
    void loginIssuesJwtAndHashedRefreshTokenAndRecordsAuditLog() {
        UserEntity user = buildUser(USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "DocuMind@2026!", Role.DEPARTMENT_MANAGER, true);
        when(userRepository.findByEmailIgnoreCase("manager.hr@documind.ai")).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogRepository.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthTokenResponse response = authService.login(
            new LoginRequest("  MANAGER.HR@documind.ai ", "DocuMind@2026!"),
            "Mozilla/5.0 " + "x".repeat(600),
            "203.0.113.10"
        );

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);
        assertThat(response.refreshExpiresIn()).isEqualTo(Duration.ofDays(7).toSeconds());
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().id()).isEqualTo(USER_ID);
        assertThat(response.user().departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(response.user().departmentCode()).isEqualTo("HR");
        assertThat(response.user().role()).isEqualTo(Role.DEPARTMENT_MANAGER);

        ArgumentCaptor<RefreshTokenEntity> tokenCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshTokenEntity stored = tokenCaptor.getValue();
        assertThat(stored.getTokenHash()).hasSize(64);
        assertThat(stored.getTokenHash()).isEqualTo(RefreshTokenHasher.sha256Hex(response.refreshToken()));
        assertThat(stored.getTokenHash()).isNotEqualTo(response.refreshToken());
        assertThat(stored.getExpiresAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED_NOW.plus(Duration.ofDays(7)), ZoneOffset.UTC));
        assertThat(stored.getUserAgent()).hasSize(512);
        assertThat(stored.getIpAddress()).isEqualTo("203.0.113.10");

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity audit = auditCaptor.getValue();
        assertThat(audit.getActionType()).isEqualTo(AuditActionType.AUTH_LOGIN);
        assertThat(audit.getStatusCode()).isEqualTo(200);
        assertThat(audit.getUser()).isSameAs(user);
        assertThat(audit.getDepartment()).isSameAs(user.getDepartment());
        assertThat(audit.getQueryText()).isNull();
    }

    @Test
    void loginRunsDummyBcryptOnUnknownEmailAndAudits401() {
        when(userRepository.findByEmailIgnoreCase("unknown@documind.ai")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
            new LoginRequest("unknown@documind.ai", "WrongPass!"),
            "TestAgent",
            "10.0.0.5"
        )).isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches(eq("WrongPass!"), anyString());
        verifyNoInteractions(refreshTokenRepository);

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActionType()).isEqualTo(AuditActionType.AUTH_LOGIN);
        assertThat(auditCaptor.getValue().getStatusCode()).isEqualTo(401);
        assertThat(auditCaptor.getValue().getUser()).isNull();
    }

    @Test
    void loginReturns403ForDisabledUserOnlyWhenPasswordMatchesAnd401WhenPasswordWrong() {
        UserEntity disabledUser = buildUser(USER_ID, HR_DEPT_ID, "disabled@documind.ai", "DocuMind@2026!", Role.STANDARD_EMPLOYEE, false);
        when(userRepository.findByEmailIgnoreCase("disabled@documind.ai")).thenReturn(Optional.of(disabledUser));

        assertThatThrownBy(() -> authService.login(
            new LoginRequest("disabled@documind.ai", "WrongPassword"),
            "TestAgent",
            "10.0.0.5"
        )).isInstanceOf(InvalidCredentialsException.class);

        assertThatThrownBy(() -> authService.login(
            new LoginRequest("disabled@documind.ai", "DocuMind@2026!"),
            "TestAgent",
            "10.0.0.5"
        )).isInstanceOf(AccountDisabledException.class);

        verifyNoInteractions(refreshTokenRepository);
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
            .extracting(AuditLogEntity::getStatusCode)
            .containsExactly(401, 403);
    }

    @Test
    void refreshRotatesTokenAtomicallyAndPreservesReplacedByHash() {
        UserEntity user = buildUser(USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "DocuMind@2026!", Role.DEPARTMENT_MANAGER, true);
        String rawOld = RefreshTokenHasher.generateOpaqueToken();
        String oldHash = RefreshTokenHasher.sha256Hex(rawOld);

        RefreshTokenEntity existing = new RefreshTokenEntity();
        existing.setId(UUID.randomUUID());
        existing.setUser(user);
        existing.setTokenHash(oldHash);
        existing.setExpiresAt(OffsetDateTime.ofInstant(FIXED_NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC));

        when(refreshTokenRepository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(existing));
        when(userRepository.findWithDepartmentById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthTokenResponse rotated = authService.refresh(new RefreshTokenRequest(rawOld), "Agent/2", "198.51.100.2");

        assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(rawOld);
        String newHash = RefreshTokenHasher.sha256Hex(rotated.refreshToken());
        assertThat(existing.getRevokedAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        assertThat(existing.getReplacedByHash()).isEqualTo(newHash);

        ArgumentCaptor<RefreshTokenEntity> savedCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getTokenHash()).isEqualTo(newHash);
    }

    @Test
    void refreshDetectsTokenReuseAndCommitsEmergencyRevocationInSingleTransaction() {
        UserEntity user = buildUser(USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "DocuMind@2026!", Role.DEPARTMENT_MANAGER, true);
        String replayedRaw = RefreshTokenHasher.generateOpaqueToken();
        String replayedHash = RefreshTokenHasher.sha256Hex(replayedRaw);

        RefreshTokenEntity rotatedAlready = new RefreshTokenEntity();
        rotatedAlready.setId(UUID.randomUUID());
        rotatedAlready.setUser(user);
        rotatedAlready.setTokenHash(replayedHash);
        rotatedAlready.setExpiresAt(OffsetDateTime.ofInstant(FIXED_NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC));
        rotatedAlready.setRevokedAt(OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(30), ZoneOffset.UTC));
        rotatedAlready.setReplacedByHash("f".repeat(64));

        when(refreshTokenRepository.findByTokenHashForUpdate(replayedHash)).thenReturn(Optional.of(rotatedAlready));
        when(refreshTokenRepository.revokeAllActiveByUserId(eq(USER_ID), any(OffsetDateTime.class))).thenReturn(1);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(replayedRaw), "Attacker", "198.51.100.99"))
            .isInstanceOf(InvalidTokenException.class)
            .satisfies(ex -> assertThat(((InvalidTokenException) ex).errorCode())
                .isEqualTo(InvalidTokenException.CODE_REFRESH_TOKEN_REUSED));

        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(USER_ID), any(OffsetDateTime.class));
        assertThat(transactionManager.propagationBehaviors)
            .containsExactly(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(transactionManager.commitCount).isEqualTo(1);
    }

    @Test
    void refreshRejectsExpiredRevokedAndDisabledUserTokens() {
        UserEntity user = buildUser(USER_ID, HR_DEPT_ID, "u@documind.ai", "DocuMind@2026!", Role.STANDARD_EMPLOYEE, true);

        String missingRaw = "missing-token";
        when(refreshTokenRepository.findByTokenHashForUpdate(RefreshTokenHasher.sha256Hex(missingRaw)))
            .thenReturn(Optional.empty());
        assertInvalidTokenCode(() -> authService.refresh(new RefreshTokenRequest(missingRaw), null, null),
            InvalidTokenException.CODE_INVALID_TOKEN);

        String loggedOutRaw = "logged-out-token";
        RefreshTokenEntity loggedOut = new RefreshTokenEntity();
        loggedOut.setUser(user);
        loggedOut.setTokenHash(RefreshTokenHasher.sha256Hex(loggedOutRaw));
        loggedOut.setExpiresAt(OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(300), ZoneOffset.UTC));
        loggedOut.setRevokedAt(OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(10), ZoneOffset.UTC));
        when(refreshTokenRepository.findByTokenHashForUpdate(loggedOut.getTokenHash())).thenReturn(Optional.of(loggedOut));
        assertInvalidTokenCode(() -> authService.refresh(new RefreshTokenRequest(loggedOutRaw), null, null),
            InvalidTokenException.CODE_TOKEN_REVOKED);

        String expiredRaw = "expired-token";
        RefreshTokenEntity expired = new RefreshTokenEntity();
        expired.setUser(user);
        expired.setTokenHash(RefreshTokenHasher.sha256Hex(expiredRaw));
        expired.setExpiresAt(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        when(refreshTokenRepository.findByTokenHashForUpdate(expired.getTokenHash())).thenReturn(Optional.of(expired));
        assertInvalidTokenCode(() -> authService.refresh(new RefreshTokenRequest(expiredRaw), null, null),
            InvalidTokenException.CODE_TOKEN_EXPIRED);

        transactionManager.propagationBehaviors.clear();
        transactionManager.commitCount = 0;
        UserEntity disabledUser = buildUser(USER_ID, HR_DEPT_ID, "u@documind.ai", "DocuMind@2026!", Role.STANDARD_EMPLOYEE, false);
        String disabledRaw = "disabled-user-token";
        RefreshTokenEntity validTokenForDisabledUser = new RefreshTokenEntity();
        validTokenForDisabledUser.setUser(disabledUser);
        validTokenForDisabledUser.setTokenHash(RefreshTokenHasher.sha256Hex(disabledRaw));
        validTokenForDisabledUser.setExpiresAt(OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(600), ZoneOffset.UTC));
        when(refreshTokenRepository.findByTokenHashForUpdate(validTokenForDisabledUser.getTokenHash()))
            .thenReturn(Optional.of(validTokenForDisabledUser));
        when(userRepository.findWithDepartmentById(USER_ID)).thenReturn(Optional.of(disabledUser));
        assertInvalidTokenCode(() -> authService.refresh(new RefreshTokenRequest(disabledRaw), null, null),
            InvalidTokenException.CODE_INVALID_TOKEN);
        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(USER_ID), any(OffsetDateTime.class));
        assertThat(transactionManager.propagationBehaviors)
            .containsExactly(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(transactionManager.commitCount).isEqualTo(1);
    }

    @Test
    void logoutBlacklistsJtiAndRevokesSingleSessionRotatedChainOrAllSessionsAndValidatesBeforeBlacklisting() {
        Instant tokenExp = FIXED_NOW.plus(Duration.ofMinutes(10));
        CustomUserDetails principal = new CustomUserDetails(
            USER_ID,
            HR_DEPT_ID,
            "manager.hr@documind.ai",
            "HR Manager",
            Role.DEPARTMENT_MANAGER,
            "jti-logout-1",
            tokenExp
        );

        // Cross-user refresh token -> reject 403 AccessDeniedException BEFORE blacklisting caller's jti
        String victimRaw = "victim-refresh-token";
        String victimHash = RefreshTokenHasher.sha256Hex(victimRaw);
        RefreshTokenEntity victimToken = new RefreshTokenEntity();
        victimToken.setUser(buildUser(OTHER_USER_ID, HR_DEPT_ID, "other@documind.ai", "p", Role.STANDARD_EMPLOYEE, true));
        victimToken.setTokenHash(victimHash);
        when(refreshTokenRepository.findByTokenHash(victimHash)).thenReturn(Optional.of(victimToken));

        assertThatThrownBy(() -> authService.logout(principal, new LogoutRequest(victimRaw), "10.0.0.8"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(victimToken.getRevokedAt()).isNull();
        verifyNoInteractions(tokenBlacklistService);

        // Unknown refresh token -> reject 401 BEFORE blacklisting caller's jti
        String unknownRaw = "unknown-refresh-token";
        when(refreshTokenRepository.findByTokenHash(RefreshTokenHasher.sha256Hex(unknownRaw))).thenReturn(Optional.empty());
        assertInvalidTokenCode(
            () -> authService.logout(principal, new LogoutRequest(unknownRaw), "10.0.0.8"),
            InvalidTokenException.CODE_INVALID_TOKEN
        );
        verifyNoInteractions(tokenBlacklistService);

        // Owned active refresh token -> blacklists jti and revokes single token
        String rawOwned = "owned-refresh-token";
        String ownedHash = RefreshTokenHasher.sha256Hex(rawOwned);
        RefreshTokenEntity owned = new RefreshTokenEntity();
        owned.setUser(buildUser(USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "p", Role.DEPARTMENT_MANAGER, true));
        owned.setTokenHash(ownedHash);
        when(refreshTokenRepository.findByTokenHash(ownedHash)).thenReturn(Optional.of(owned));
        when(refreshTokenRepository.findByTokenHashForUpdate(ownedHash)).thenReturn(Optional.of(owned));

        authService.logout(principal, new LogoutRequest(rawOwned), "10.0.0.8");

        verify(tokenBlacklistService).blacklist("jti-logout-1", tokenExp);
        assertThat(owned.getRevokedAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any(), any());

        // Owned already-rotated refresh token (raced in-flight refresh) -> revokes replacement chain (all active sessions)
        String rawRotated = "rotated-refresh-token";
        String rotatedHash = RefreshTokenHasher.sha256Hex(rawRotated);
        RefreshTokenEntity rotatedOld = new RefreshTokenEntity();
        rotatedOld.setUser(buildUser(USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "p", Role.DEPARTMENT_MANAGER, true));
        rotatedOld.setTokenHash(rotatedHash);
        rotatedOld.setRevokedAt(OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(5), ZoneOffset.UTC));
        rotatedOld.setReplacedByHash("a".repeat(64));
        when(refreshTokenRepository.findByTokenHash(rotatedHash)).thenReturn(Optional.of(rotatedOld));
        when(refreshTokenRepository.findByTokenHashForUpdate(rotatedHash)).thenReturn(Optional.of(rotatedOld));

        authService.logout(principal, new LogoutRequest(rawRotated), "10.0.0.8");
        verify(refreshTokenRepository).revokeAllActiveByUserId(USER_ID, OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));

        // Without refreshToken -> revoke all sessions for caller
        authService.logout(principal, null, "10.0.0.8");
        verify(refreshTokenRepository, org.mockito.Mockito.times(2))
            .revokeAllActiveByUserId(USER_ID, OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void getCurrentUserLoadsFreshProfileAndRejectsInactiveUsers() {
        UserEntity active = buildUser(USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "hash", Role.DEPARTMENT_MANAGER, true);
        active.setCreatedAt(OffsetDateTime.ofInstant(FIXED_NOW.minus(Duration.ofDays(30)), ZoneOffset.UTC));
        when(userRepository.findWithDepartmentById(USER_ID)).thenReturn(Optional.of(active));

        CustomUserDetails principal = new CustomUserDetails(
            USER_ID, HR_DEPT_ID, "manager.hr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER, "jti", FIXED_NOW
        );
        UserProfileResponse profile = authService.getCurrentUser(principal);
        assertThat(profile.id()).isEqualTo(USER_ID);
        assertThat(profile.departmentCode()).isEqualTo("HR");
        assertThat(profile.isActive()).isTrue();

        active.setIsActive(false);
        assertInvalidTokenCode(() -> authService.getCurrentUser(principal), InvalidTokenException.CODE_INVALID_TOKEN);
    }

    private static void assertInvalidTokenCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String expectedCode) {
        assertThatThrownBy(callable)
            .isInstanceOf(InvalidTokenException.class)
            .satisfies(ex -> assertThat(((InvalidTokenException) ex).errorCode()).isEqualTo(expectedCode));
    }

    private UserEntity buildUser(UUID id, UUID deptId, String email, String rawPassword, Role role, boolean active) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(deptId);
        dept.setCode("HR");
        dept.setName("Human Resources");

        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDepartment(dept);
        user.setEmail(email);
        user.setFullName("HR Manager");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setIsActive(active);
        return user;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<Integer> propagationBehaviors = new ArrayList<>();
        private int commitCount;

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
            propagationBehaviors.add(definition.getPropagationBehavior());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            // No-op
        }
    }
}
