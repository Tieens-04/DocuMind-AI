package com.documind.service.auth;

import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.auth.LoginRequest;
import com.documind.controller.dto.auth.LogoutRequest;
import com.documind.controller.dto.auth.RefreshTokenRequest;
import com.documind.controller.dto.auth.UserProfileResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.RefreshTokenEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.exception.AccountDisabledException;
import com.documind.exception.AuthenticationRequiredException;
import com.documind.exception.InvalidCredentialsException;
import com.documind.exception.InvalidTokenException;
import com.documind.infrastructure.security.ClientIpResolver;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.security.JwtService;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.RefreshTokenRepository;
import com.documind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Orchestrates stateless JWT login, single-transaction pessimistic-locked refresh token rotation
 * with reuse detection, fail-closed logout revocation, and caller profile retrieval.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final Supplier<UserRepository> userRepositorySupplier;
    private final Supplier<RefreshTokenRepository> refreshTokenRepositorySupplier;
    private final Supplier<AuditLogRepository> auditLogRepositorySupplier;
    private final Supplier<PlatformTransactionManager> transactionManagerSupplier;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final Clock clock;
    private final String dummyPasswordHash;

    @Autowired
    public AuthService(
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<RefreshTokenRepository> refreshTokenRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        TokenBlacklistService tokenBlacklistService
    ) {
        this(
            () -> Objects.requireNonNull(userRepositoryProvider, "userRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(refreshTokenRepositoryProvider, "refreshTokenRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(auditLogRepositoryProvider, "auditLogRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(transactionManagerProvider, "transactionManagerProvider must not be null").getObject(),
            passwordEncoder,
            jwtService,
            tokenBlacklistService,
            Clock.systemUTC()
        );
    }

    public AuthService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        AuditLogRepository auditLogRepository,
        PlatformTransactionManager transactionManager,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        TokenBlacklistService tokenBlacklistService,
        Clock clock
    ) {
        this(
            () -> Objects.requireNonNull(userRepository, "userRepository must not be null"),
            () -> Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository must not be null"),
            () -> Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null"),
            () -> Objects.requireNonNull(transactionManager, "transactionManager must not be null"),
            passwordEncoder,
            jwtService,
            tokenBlacklistService,
            clock
        );
    }

    private AuthService(
        Supplier<UserRepository> userRepositorySupplier,
        Supplier<RefreshTokenRepository> refreshTokenRepositorySupplier,
        Supplier<AuditLogRepository> auditLogRepositorySupplier,
        Supplier<PlatformTransactionManager> transactionManagerSupplier,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        TokenBlacklistService tokenBlacklistService,
        Clock clock
    ) {
        this.userRepositorySupplier = Objects.requireNonNull(userRepositorySupplier, "userRepositorySupplier must not be null");
        this.refreshTokenRepositorySupplier = Objects.requireNonNull(refreshTokenRepositorySupplier, "refreshTokenRepositorySupplier must not be null");
        this.auditLogRepositorySupplier = Objects.requireNonNull(auditLogRepositorySupplier, "auditLogRepositorySupplier must not be null");
        this.transactionManagerSupplier = Objects.requireNonNull(transactionManagerSupplier, "transactionManagerSupplier must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.tokenBlacklistService = Objects.requireNonNull(tokenBlacklistService, "tokenBlacklistService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.dummyPasswordHash = this.passwordEncoder.encode("documind-dummy-" + UUID.randomUUID());
    }

    /**
     * Authenticates a user with email/password outside any write transaction, then atomically persists
     * a hashed refresh token and {@code AUTH_LOGIN} audit log.
     */
    public AuthTokenResponse login(LoginRequest request, String userAgent, String ipAddress) {
        long startedAtNanos = System.nanoTime();
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String safeIp = ClientIpResolver.normalize(ipAddress);
        String safeUserAgent = truncate(userAgent, MAX_USER_AGENT_LENGTH);

        Optional<UserEntity> foundUser = userRepositorySupplier.get().findByEmailIgnoreCase(normalizedEmail);
        if (foundUser.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            recordAuthAudit(null, AuditActionType.AUTH_LOGIN, HttpStatus.UNAUTHORIZED.value(), safeIp, elapsedMillis(startedAtNanos));
            throw new InvalidCredentialsException("Invalid email or password");
        }

        UserEntity user = foundUser.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            recordAuthAudit(user, AuditActionType.AUTH_LOGIN, HttpStatus.UNAUTHORIZED.value(), safeIp, elapsedMillis(startedAtNanos));
            throw new InvalidCredentialsException("Invalid email or password");
        }

        if (!user.isActive()) {
            recordAuthAudit(user, AuditActionType.AUTH_LOGIN, HttpStatus.FORBIDDEN.value(), safeIp, elapsedMillis(startedAtNanos));
            throw new AccountDisabledException("User account is disabled");
        }

        JwtService.IssuedAccessToken accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = RefreshTokenHasher.generateOpaqueToken();
        String refreshHash = RefreshTokenHasher.sha256Hex(rawRefreshToken);
        OffsetDateTime now = nowUtc();
        OffsetDateTime refreshExpiresAt = now.plus(jwtService.refreshTokenTtl());

        executeInTransaction(false, () -> {
            persistRefreshToken(user, refreshHash, refreshExpiresAt, safeUserAgent, safeIp, now);
            saveAuditEntry(user, AuditActionType.AUTH_LOGIN, HttpStatus.OK.value(), safeIp, elapsedMillis(startedAtNanos));
            return null;
        });

        return buildTokenResponse(accessToken, rawRefreshToken, user);
    }

    /**
     * Rotates a valid refresh token under {@code SELECT ... FOR UPDATE} inside a single database
     * transaction, committing emergency session-wide revocation before throwing on reuse or inactive users.
     */
    public AuthTokenResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress) {
        String presentedHash = RefreshTokenHasher.sha256Hex(request.refreshToken().trim());
        String safeIp = ClientIpResolver.normalize(ipAddress);
        String safeUserAgent = truncate(userAgent, MAX_USER_AGENT_LENGTH);

        RefreshTxOutcome outcome = executeInTransaction(false, () -> {
            RefreshTokenEntity storedToken = refreshTokenRepositorySupplier.get()
                .findByTokenHashForUpdate(presentedHash)
                .orElse(null);
            if (storedToken == null) {
                return new RefreshTxOutcome.Rejected(
                    InvalidTokenException.CODE_INVALID_TOKEN,
                    "Refresh token is invalid"
                );
            }

            OffsetDateTime now = nowUtc();
            UUID userId = storedToken.getUserId();

            if (storedToken.getRevokedAt() != null) {
                if (storedToken.getReplacedByHash() != null) {
                    refreshTokenRepositorySupplier.get().revokeAllActiveByUserId(userId, now);
                    log.warn("Refresh token reuse detected for userId={}; all active refresh tokens revoked", userId);
                    return new RefreshTxOutcome.Rejected(
                        InvalidTokenException.CODE_REFRESH_TOKEN_REUSED,
                        "Refresh token reuse detected; all sessions have been revoked"
                    );
                }
                return new RefreshTxOutcome.Rejected(
                    InvalidTokenException.CODE_TOKEN_REVOKED,
                    "Refresh token has been revoked"
                );
            }

            if (!storedToken.getExpiresAt().isAfter(now)) {
                return new RefreshTxOutcome.Rejected(
                    InvalidTokenException.CODE_TOKEN_EXPIRED,
                    "Refresh token has expired"
                );
            }

            UserEntity user = userRepositorySupplier.get()
                .findWithDepartmentById(userId)
                .orElse(null);
            if (user == null || !user.isActive()) {
                refreshTokenRepositorySupplier.get().revokeAllActiveByUserId(userId, now);
                return new RefreshTxOutcome.Rejected(
                    InvalidTokenException.CODE_INVALID_TOKEN,
                    "User account is inactive or no longer exists"
                );
            }

            JwtService.IssuedAccessToken accessToken = jwtService.generateAccessToken(user);
            String newRawRefreshToken = RefreshTokenHasher.generateOpaqueToken();
            String newRefreshHash = RefreshTokenHasher.sha256Hex(newRawRefreshToken);

            storedToken.setRevokedAt(now);
            storedToken.setReplacedByHash(newRefreshHash);

            persistRefreshToken(
                user,
                newRefreshHash,
                now.plus(jwtService.refreshTokenTtl()),
                safeUserAgent,
                safeIp,
                now
            );

            return new RefreshTxOutcome.Success(buildTokenResponse(accessToken, newRawRefreshToken, user));
        });

        if (outcome instanceof RefreshTxOutcome.Success(AuthTokenResponse response)) {
            return response;
        }
        RefreshTxOutcome.Rejected rejected = (RefreshTxOutcome.Rejected) outcome;
        throw new InvalidTokenException(rejected.errorCode(), rejected.message());
    }

    /**
     * Validates optional refresh token ownership first, blacklists the caller's access token {@code jti}
     * in Redis (fail-closed, outside any DB transaction), then revokes the target token (or its entire
     * active family if already rotated) / all active sessions and logs {@code AUTH_LOGOUT}.
     */
    public void logout(CustomUserDetails principal, LogoutRequest request, String ipAddress) {
        if (principal == null) {
            throw new AuthenticationRequiredException("Authenticated security principal is required");
        }
        long startedAtNanos = System.nanoTime();
        String safeIp = ClientIpResolver.normalize(ipAddress);

        String rawRefreshToken = (request != null && request.refreshToken() != null && !request.refreshToken().isBlank())
            ? request.refreshToken().trim()
            : null;
        String tokenHash = rawRefreshToken != null ? RefreshTokenHasher.sha256Hex(rawRefreshToken) : null;

        if (tokenHash != null) {
            RefreshTokenEntity candidate = refreshTokenRepositorySupplier.get()
                .findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                    InvalidTokenException.CODE_INVALID_TOKEN,
                    "Refresh token is invalid"
                ));
            if (!principal.userId().equals(candidate.getUserId())) {
                throw new AccessDeniedException("Refresh token does not belong to the authenticated user");
            }
        }

        if (principal.tokenJti() != null && principal.tokenExpiresAt() != null) {
            tokenBlacklistService.blacklist(principal.tokenJti(), principal.tokenExpiresAt());
        }

        OffsetDateTime now = nowUtc();
        executeInTransaction(false, () -> {
            if (tokenHash != null) {
                RefreshTokenEntity storedToken = refreshTokenRepositorySupplier.get()
                    .findByTokenHashForUpdate(tokenHash)
                    .orElseThrow(() -> new InvalidTokenException(
                        InvalidTokenException.CODE_INVALID_TOKEN,
                        "Refresh token is invalid"
                    ));
                if (!principal.userId().equals(storedToken.getUserId())) {
                    throw new AccessDeniedException("Refresh token does not belong to the authenticated user");
                }
                if (storedToken.getReplacedByHash() != null) {
                    refreshTokenRepositorySupplier.get().revokeAllActiveByUserId(principal.userId(), now);
                } else if (storedToken.getRevokedAt() == null) {
                    storedToken.setRevokedAt(now);
                }
            } else {
                refreshTokenRepositorySupplier.get().revokeAllActiveByUserId(principal.userId(), now);
            }

            UserEntity user = userRepositorySupplier.get()
                .findWithDepartmentById(principal.userId())
                .orElse(null);
            saveAuditEntry(user, AuditActionType.AUTH_LOGOUT, HttpStatus.NO_CONTENT.value(), safeIp, elapsedMillis(startedAtNanos));
            return null;
        });
    }

    /**
     * Loads fresh profile and department details for the authenticated user, rejecting deactivated accounts.
     */
    public UserProfileResponse getCurrentUser(CustomUserDetails principal) {
        if (principal == null) {
            throw new AuthenticationRequiredException("Authenticated security principal is required");
        }
        return executeInTransaction(true, () -> {
            UserEntity user = userRepositorySupplier.get()
                .findWithDepartmentById(principal.userId())
                .orElseThrow(() -> new InvalidTokenException(
                    InvalidTokenException.CODE_INVALID_TOKEN,
                    "User account no longer exists"
                ));
            if (!user.isActive()) {
                throw new InvalidTokenException(
                    InvalidTokenException.CODE_INVALID_TOKEN,
                    "User account is disabled"
                );
            }
            return UserProfileResponse.fromEntity(user);
        });
    }

    private <T> T executeInTransaction(boolean readOnly, Supplier<T> action) {
        PlatformTransactionManager txManager = Objects.requireNonNull(
            transactionManagerSupplier.get(),
            "PlatformTransactionManager must not be null"
        );
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(readOnly);
        return template.execute(status -> action.get());
    }

    private void persistRefreshToken(
        UserEntity user,
        String tokenHash,
        OffsetDateTime expiresAt,
        String userAgent,
        String ipAddress,
        OffsetDateTime createdAt
    ) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUser(user);
        entity.setTokenHash(tokenHash);
        entity.setExpiresAt(expiresAt);
        entity.setUserAgent(userAgent);
        entity.setIpAddress(ipAddress);
        entity.setCreatedAt(createdAt);
        refreshTokenRepositorySupplier.get().save(entity);
    }

    private void recordAuthAudit(
        UserEntity user,
        AuditActionType actionType,
        int statusCode,
        String ipAddress,
        long latencyMs
    ) {
        executeInTransaction(false, () -> {
            saveAuditEntry(user, actionType, statusCode, ipAddress, latencyMs);
            return null;
        });
    }

    private void saveAuditEntry(
        UserEntity user,
        AuditActionType actionType,
        int statusCode,
        String ipAddress,
        long latencyMs
    ) {
        AuditLogEntity entry = new AuditLogEntity();
        if (user != null) {
            entry.setUser(user);
            entry.setDepartment(user.getDepartment());
        }
        entry.setActionType(actionType);
        entry.setAbstained(false);
        entry.setCacheTier(CacheTier.NONE);
        entry.setPromptTokens(0);
        entry.setCompletionTokens(0);
        entry.setLatencyMs(Math.max(0L, latencyMs));
        entry.setStatusCode(statusCode);
        entry.setIpAddress(ipAddress);
        auditLogRepositorySupplier.get().save(entry);
    }

    private AuthTokenResponse buildTokenResponse(
        JwtService.IssuedAccessToken accessToken,
        String rawRefreshToken,
        UserEntity user
    ) {
        return new AuthTokenResponse(
            accessToken.token(),
            rawRefreshToken,
            TOKEN_TYPE_BEARER,
            accessToken.expiresInSeconds(),
            jwtService.refreshTokenTtl().toSeconds(),
            UserProfileResponse.fromEntity(user)
        );
    }

    private OffsetDateTime nowUtc() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private sealed interface RefreshTxOutcome permits RefreshTxOutcome.Success, RefreshTxOutcome.Rejected {
        record Success(AuthTokenResponse response) implements RefreshTxOutcome {}
        record Rejected(String errorCode, String message) implements RefreshTxOutcome {}
    }
}
