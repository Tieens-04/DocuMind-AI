package com.documind.service.admin;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminResetPasswordRequest;
import com.documind.controller.dto.admin.AdminUserResponse;
import com.documind.controller.dto.admin.CreateUserRequest;
import com.documind.controller.dto.admin.UpdateUserRequest;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.AdminConflictException;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.security.JwtService;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.RefreshTokenRepository;
import com.documind.repository.UserRepository;
import com.documind.service.auth.TokenBlacklistService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative user management service restricted to active {@code SYSTEM_ADMIN} actors.
 * Enforces soft-deactivation lifecycle, deterministic row locking against lost updates and last-admin races,
 * and atomic DB + Redis zero-window JWT revocation on security-sensitive changes.
 */
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MAX_EMAIL_LOCAL_LENGTH = 64;
    private static final int MAX_FULL_NAME_LENGTH = 150;

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PlatformTransactionManager transactionManager;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final JwtService jwtService;
    private final Clock clock;

    @Autowired
    public AdminUserService(
        @Lazy UserRepository userRepository,
        @Lazy DepartmentRepository departmentRepository,
        @Lazy RefreshTokenRepository refreshTokenRepository,
        @Lazy PlatformTransactionManager transactionManager,
        PasswordEncoder passwordEncoder,
        TokenBlacklistService tokenBlacklistService,
        JwtService jwtService
    ) {
        this(
            userRepository,
            departmentRepository,
            refreshTokenRepository,
            transactionManager,
            passwordEncoder,
            tokenBlacklistService,
            jwtService,
            Clock.systemUTC()
        );
    }

    public AdminUserService(
        UserRepository userRepository,
        DepartmentRepository departmentRepository,
        RefreshTokenRepository refreshTokenRepository,
        PlatformTransactionManager transactionManager,
        PasswordEncoder passwordEncoder,
        TokenBlacklistService tokenBlacklistService,
        JwtService jwtService,
        Clock clock
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.departmentRepository = Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.tokenBlacklistService = Objects.requireNonNull(tokenBlacklistService, "tokenBlacklistService must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AdminUserResponse createUser(CreateUserRequest request, UUID actorUserId) {
        if (request == null) {
            throw new InvalidAdminRequestException("Create user request must not be null");
        }
        String normalizedEmail = normalizeAndValidateEmail(request.email());
        String normalizedFullName = normalizeRequiredFullName(request.fullName());
        String validatedPassword = validatePassword(request.password());
        if (request.role() == null) {
            throw new InvalidAdminRequestException("Role must not be null");
        }
        if (request.departmentId() == null) {
            throw new InvalidAdminRequestException("Department ID must not be null");
        }
        boolean active = request.isActive() == null || Boolean.TRUE.equals(request.isActive());

        // Hash BCrypt outside the DB transaction so HikariCP connections are never held during CPU work
        String passwordHash = passwordEncoder.encode(validatedPassword);

        AdminUserResponse created;
        try {
            created = AdminServiceSupport.executeInTransaction(transactionManager, false, () -> {
                AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
                if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                    throw new AdminConflictException(
                        AdminConflictException.CODE_DUPLICATE_EMAIL,
                        "A user with email '" + normalizedEmail + "' already exists"
                    );
                }

                DepartmentEntity department = departmentRepository
                    .findById(request.departmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));

                UserEntity entity = new UserEntity();
                entity.setDepartment(department);
                entity.setEmail(normalizedEmail);
                entity.setPasswordHash(passwordHash);
                entity.setFullName(normalizedFullName);
                entity.setRole(request.role());
                entity.setIsActive(active);

                UserEntity saved = userRepository.saveAndFlush(entity);
                return AdminUserResponse.fromEntity(saved);
            });
        } catch (DataIntegrityViolationException ex) {
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                throw new AdminConflictException(
                    AdminConflictException.CODE_DUPLICATE_EMAIL,
                    "A user with email '" + normalizedEmail + "' already exists",
                    ex
                );
            }
            if (!departmentRepository.existsById(request.departmentId())) {
                throw new ResourceNotFoundException("Department not found: " + request.departmentId());
            }
            throw ex;
        }

        log.info(
            "SECURITY_AUDIT action=ADMIN_USER_CREATE actorUserId={} targetUserId={} role={} departmentId={} isActive={}",
            actorUserId,
            created.id(),
            created.role(),
            created.departmentId(),
            created.isActive()
        );
        return created;
    }

    public PageResponse<AdminUserResponse> listUsers(
        UUID departmentId,
        Role role,
        Boolean isActive,
        String query,
        int page,
        int size,
        UUID actorUserId
    ) {
        AdminServiceSupport.validatePaging(page, size);
        String normalizedQuery = AdminServiceSupport.normalizeSearchQuery(query);
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            Specification<UserEntity> spec = (root, cq, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                if (departmentId != null) {
                    predicates.add(cb.equal(root.get("departmentId"), departmentId));
                }
                if (role != null) {
                    predicates.add(cb.equal(root.get("role"), role));
                }
                if (isActive != null) {
                    predicates.add(cb.equal(root.get("isActive"), isActive));
                }
                if (normalizedQuery != null) {
                    String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);
                    predicates.add(cb.or(
                        cb.greaterThan(cb.locate(cb.lower(root.get("email")), lowerQuery), 0),
                        cb.greaterThan(cb.locate(cb.lower(root.get("fullName")), lowerQuery), 0)
                    ));
                }
                return predicates.isEmpty() ? null : cb.and(predicates.toArray(Predicate[]::new));
            };
            PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<AdminUserResponse> result = userRepository
                .findAll(spec, pageable)
                .map(AdminUserResponse::fromEntity);
            return PageResponse.from(result);
        });
    }

    public AdminUserResponse getUser(UUID userId, UUID actorUserId) {
        if (userId == null) {
            throw new InvalidAdminRequestException("User ID must not be null");
        }
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            UserEntity user = userRepository
                .findWithDepartmentById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
            return AdminUserResponse.fromEntity(user);
        });
    }

    public AdminUserResponse updateUser(UUID userId, UpdateUserRequest request, UUID actorUserId) {
        if (userId == null) {
            throw new InvalidAdminRequestException("User ID must not be null");
        }
        if (request == null
            || (request.email() == null
                && request.fullName() == null
                && request.role() == null
                && request.departmentId() == null
                && request.isActive() == null)) {
            throw new InvalidAdminRequestException(
                "At least one updatable field (email, fullName, role, departmentId, isActive) must be provided"
            );
        }

        String normalizedEmail = request.email() != null ? normalizeAndValidateEmail(request.email()) : null;
        String normalizedFullName = request.fullName() != null ? normalizeRequiredFullName(request.fullName()) : null;

        if (actorUserId != null && actorUserId.equals(userId)) {
            if (Boolean.FALSE.equals(request.isActive())) {
                throw new AdminConflictException(
                    AdminConflictException.CODE_ADMIN_SELF_MODIFICATION_FORBIDDEN,
                    "System administrators cannot deactivate their own account"
                );
            }
            if (request.role() != null && request.role() != Role.SYSTEM_ADMIN) {
                throw new AdminConflictException(
                    AdminConflictException.CODE_ADMIN_SELF_MODIFICATION_FORBIDDEN,
                    "System administrators cannot demote their own role"
                );
            }
        }

        UserUpdateOutcome outcome;
        try {
            outcome = AdminServiceSupport.executeInTransaction(transactionManager, false, () -> {
                // Acquire deterministic ascending-ID row locks BEFORE loading JPA entities to prevent
                // lost updates, stale actor reads, and 40P01 deadlocks on concurrent admin mutations.
                if (actorUserId != null) {
                    userRepository.lockActorTargetAndActiveAdminsForUpdate(actorUserId, userId);
                }
                AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);

                UserEntity user = userRepository.findWithDepartmentById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

                boolean wasActiveAdmin = user.getRole() == Role.SYSTEM_ADMIN && user.isActive();
                Role targetRole = request.role() != null ? request.role() : user.getRole();
                boolean targetActive = request.isActive() != null ? request.isActive() : user.isActive();
                boolean losingActiveAdmin = wasActiveAdmin && (targetRole != Role.SYSTEM_ADMIN || !targetActive);

                if (losingActiveAdmin) {
                    List<UUID> activeAdminIds = userRepository.findActiveSystemAdminIdsForUpdate();
                    if (activeAdminIds.isEmpty()
                        || (activeAdminIds.size() == 1 && activeAdminIds.contains(userId))) {
                        throw new AdminConflictException(
                            AdminConflictException.CODE_LAST_ACTIVE_ADMIN,
                            "Cannot deactivate or demote the last active SYSTEM_ADMIN account"
                        );
                    }
                }

                boolean securityStateChanged = false;

                if (normalizedEmail != null && !normalizedEmail.equalsIgnoreCase(user.getEmail())) {
                    if (userRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, userId)) {
                        throw new AdminConflictException(
                            AdminConflictException.CODE_DUPLICATE_EMAIL,
                            "A user with email '" + normalizedEmail + "' already exists"
                        );
                    }
                    user.setEmail(normalizedEmail);
                    securityStateChanged = true;
                }

                if (normalizedFullName != null) {
                    user.setFullName(normalizedFullName);
                }

                if (request.role() != null && request.role() != user.getRole()) {
                    user.setRole(request.role());
                    securityStateChanged = true;
                }

                if (request.departmentId() != null && !Objects.equals(request.departmentId(), user.getDepartmentId())) {
                    DepartmentEntity targetDepartment = departmentRepository
                        .findById(request.departmentId())
                        .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));
                    user.setDepartment(targetDepartment);
                    securityStateChanged = true;
                }

                if (request.isActive() != null && request.isActive() != user.isActive()) {
                    user.setIsActive(request.isActive());
                    if (!request.isActive()) {
                        securityStateChanged = true;
                    }
                }

                boolean shouldRevokeSessions = securityStateChanged || Boolean.FALSE.equals(request.isActive());

                UserEntity saved = userRepository.saveAndFlush(user);
                if (shouldRevokeSessions) {
                    refreshTokenRepository.revokeAllActiveByUserId(userId, nowUtc());
                    // Execute Redis revocation inside the transaction so transient Redis errors roll back DB cleanly
                    tokenBlacklistService.revokeAllUserTokens(userId, jwtService.accessTokenTtl());
                }
                return new UserUpdateOutcome(AdminUserResponse.fromEntity(saved), shouldRevokeSessions);
            });
        } catch (DataIntegrityViolationException ex) {
            if (normalizedEmail != null && userRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, userId)) {
                throw new AdminConflictException(
                    AdminConflictException.CODE_DUPLICATE_EMAIL,
                    "Email conflicts with an existing user account",
                    ex
                );
            }
            if (request.departmentId() != null && !departmentRepository.existsById(request.departmentId())) {
                throw new ResourceNotFoundException("Department not found: " + request.departmentId());
            }
            throw ex;
        }

        log.info(
            "SECURITY_AUDIT action=ADMIN_USER_UPDATE actorUserId={} targetUserId={} role={} departmentId={} isActive={} sessionsRevoked={}",
            actorUserId,
            outcome.response().id(),
            outcome.response().role(),
            outcome.response().departmentId(),
            outcome.response().isActive(),
            outcome.sessionsRevoked()
        );
        return outcome.response();
    }

    public void resetPassword(UUID userId, AdminResetPasswordRequest request, UUID actorUserId) {
        if (userId == null) {
            throw new InvalidAdminRequestException("User ID must not be null");
        }
        if (request == null) {
            throw new InvalidAdminRequestException("Reset password request must not be null");
        }
        String validatedPassword = validatePassword(request.newPassword());

        // Hash outside the DB transaction so DB connections are not held during BCrypt CPU work
        String passwordHash = passwordEncoder.encode(validatedPassword);

        AdminServiceSupport.executeInTransaction(transactionManager, false, () -> {
            if (actorUserId != null) {
                userRepository.lockUsersInOrderForUpdate(actorUserId, userId);
            }
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);

            UserEntity user = userRepository.findWithDepartmentById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
            user.setPasswordHash(passwordHash);
            userRepository.saveAndFlush(user);
            refreshTokenRepository.revokeAllActiveByUserId(userId, nowUtc());
            // Fail-closed: roll back password change if Redis revocation store is unavailable
            tokenBlacklistService.revokeAllUserTokens(userId, jwtService.accessTokenTtl());
            return null;
        });

        log.info(
            "SECURITY_AUDIT action=ADMIN_USER_PASSWORD_RESET actorUserId={} targetUserId={} sessionsRevoked=true",
            actorUserId,
            userId
        );
    }

    private OffsetDateTime nowUtc() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String normalizeAndValidateEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new InvalidAdminRequestException("Email must not be blank");
        }
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            throw new InvalidAdminRequestException("Email must not exceed " + MAX_EMAIL_LENGTH + " characters");
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex <= 0 || atIndex != normalized.lastIndexOf('@') || atIndex == normalized.length() - 1) {
            throw new InvalidAdminRequestException("Email must be a valid email address");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isWhitespace(normalized.charAt(i))) {
                throw new InvalidAdminRequestException("Email must be a valid email address");
            }
        }
        String localPart = normalized.substring(0, atIndex);
        String domainPart = normalized.substring(atIndex + 1);
        if (localPart.length() > MAX_EMAIL_LOCAL_LENGTH
            || localPart.startsWith(".")
            || localPart.endsWith(".")
            || localPart.contains("..")
            || !domainPart.contains(".")
            || domainPart.startsWith(".")
            || domainPart.endsWith(".")
            || domainPart.contains("..")) {
            throw new InvalidAdminRequestException("Email must be a valid email address");
        }
        return normalized;
    }

    private static String normalizeRequiredFullName(String rawFullName) {
        if (rawFullName == null || rawFullName.isBlank()) {
            throw new InvalidAdminRequestException("Full name must not be blank");
        }
        String normalized = Normalizer.normalize(rawFullName.trim(), Normalizer.Form.NFC);
        if (normalized.length() > MAX_FULL_NAME_LENGTH) {
            throw new InvalidAdminRequestException(
                "Full name must not exceed " + MAX_FULL_NAME_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static String validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new InvalidAdminRequestException("Password must not be blank");
        }
        if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidAdminRequestException(
                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"
            );
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_UTF8_BYTES) {
            throw new InvalidAdminRequestException(
                "Password must not exceed " + MAX_PASSWORD_UTF8_BYTES + " UTF-8 bytes"
            );
        }
        return rawPassword;
    }

    private record UserUpdateOutcome(AdminUserResponse response, boolean sessionsRevoked) {
    }
}
