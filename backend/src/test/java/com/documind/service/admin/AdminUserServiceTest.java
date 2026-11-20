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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

    private static final UUID ADMIN_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ADMIN_B_ID = UUID.fromString("aaaaaaa1-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant FIXED_NOW = Instant.parse("2026-11-19T12:00:00Z");

    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordEncoder passwordEncoder;
    private TokenBlacklistService tokenBlacklistService;
    private JwtService jwtService;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = new BCryptPasswordEncoder(4);
        tokenBlacklistService = mock(TokenBlacklistService.class);
        jwtService = mock(JwtService.class);
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT", "IT");
        when(userRepository.findById(ADMIN_A_ID)).thenReturn(
            Optional.of(user(ADMIN_A_ID, itDept, "admin@documind.ai", "Admin A", Role.SYSTEM_ADMIN, true))
        );

        service = new AdminUserService(
            userRepository,
            departmentRepository,
            refreshTokenRepository,
            new StubTransactionManager(),
            passwordEncoder,
            tokenBlacklistService,
            jwtService,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createUserNormalizesEmailHashesPasswordAndRejectsDuplicatesOrMissingDepartment() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR", "Human Resources");
        when(departmentRepository.findById(HR_DEPT_ID)).thenReturn(Optional.of(hrDept));
        when(userRepository.existsByEmailIgnoreCase("new.user@documind.ai")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity entity = inv.getArgument(0);
            entity.setId(USER_ID);
            OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            assertThat(passwordEncoder.matches("Secret@2026!", entity.getPasswordHash())).isTrue();
            return entity;
        });

        AdminUserResponse created = service.createUser(
            new CreateUserRequest(
                "  New.User@DocuMind.AI ",
                "  Nguyễn Văn A  ",
                "Secret@2026!",
                Role.DEPARTMENT_MANAGER,
                HR_DEPT_ID,
                null
            ),
            ADMIN_A_ID
        );

        assertThat(created.id()).isEqualTo(USER_ID);
        assertThat(created.email()).isEqualTo("new.user@documind.ai");
        assertThat(created.fullName()).isEqualTo("Nguyễn Văn A");
        assertThat(created.role()).isEqualTo(Role.DEPARTMENT_MANAGER);
        assertThat(created.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(created.departmentCode()).isEqualTo("HR");
        assertThat(created.isActive()).isTrue();
        verifyNoInteractions(tokenBlacklistService);

        // Duplicate email -> 409 DUPLICATE_EMAIL
        when(userRepository.existsByEmailIgnoreCase("dup@documind.ai")).thenReturn(true);
        assertThatThrownBy(() -> service.createUser(
            new CreateUserRequest("DUP@documind.ai", "Dup", "Secret@2026!", Role.STANDARD_EMPLOYEE, HR_DEPT_ID, true),
            ADMIN_A_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DUPLICATE_EMAIL));

        // Missing department -> 404
        UUID unknownDept = UUID.randomUUID();
        when(userRepository.existsByEmailIgnoreCase("ok@documind.ai")).thenReturn(false);
        when(departmentRepository.findById(unknownDept)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createUser(
            new CreateUserRequest("ok@documind.ai", "Ok", "Secret@2026!", Role.STANDARD_EMPLOYEE, unknownDept, true),
            ADMIN_A_ID
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAndGetUsersSupportFiltersAndPagingValidation() {
        assertThatThrownBy(() -> service.listUsers(null, null, null, null, -1, 20, ADMIN_A_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.listUsers(null, null, null, null, 0, 101, ADMIN_A_ID))
            .isInstanceOf(InvalidAdminRequestException.class);

        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR", "Human Resources");
        UserEntity user = user(USER_ID, hrDept, "manager.hr@documind.ai", "HR Manager", Role.DEPARTMENT_MANAGER, true);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(userRepository.findWithDepartmentById(USER_ID)).thenReturn(Optional.of(user));

        PageResponse<AdminUserResponse> page = service.listUsers(
            HR_DEPT_ID, Role.DEPARTMENT_MANAGER, true, "manager", 0, 20, ADMIN_A_ID
        );
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().email()).isEqualTo("manager.hr@documind.ai");
        assertThat(service.getUser(USER_ID, ADMIN_A_ID).departmentCode()).isEqualTo("HR");
    }

    @Test
    void updateUserBlocksAdminSelfLockoutAndLastActiveAdminDemotion() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT", "IT");
        UserEntity selfAdmin = user(ADMIN_A_ID, itDept, "admin@documind.ai", "Admin A", Role.SYSTEM_ADMIN, true);
        when(userRepository.findWithDepartmentById(ADMIN_A_ID)).thenReturn(Optional.of(selfAdmin));

        // 1. Self deactivation blocked
        assertThatThrownBy(() -> service.updateUser(
            ADMIN_A_ID,
            new UpdateUserRequest(null, null, null, null, false),
            ADMIN_A_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_ADMIN_SELF_MODIFICATION_FORBIDDEN));

        // 2. Self demotion blocked
        assertThatThrownBy(() -> service.updateUser(
            ADMIN_A_ID,
            new UpdateUserRequest(null, null, Role.DEPARTMENT_MANAGER, null, null),
            ADMIN_A_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_ADMIN_SELF_MODIFICATION_FORBIDDEN));

        // 3. Last active SYSTEM_ADMIN deactivation blocked under pessimistic lock (both size == 1 and size == 0)
        UserEntity onlyAdmin = user(ADMIN_B_ID, itDept, "admin2@documind.ai", "Admin B", Role.SYSTEM_ADMIN, true);
        when(userRepository.findWithDepartmentById(ADMIN_B_ID)).thenReturn(Optional.of(onlyAdmin));
        when(userRepository.findActiveSystemAdminIdsForUpdate()).thenReturn(List.of(ADMIN_B_ID));

        assertThatThrownBy(() -> service.updateUser(
            ADMIN_B_ID,
            new UpdateUserRequest(null, null, null, null, false),
            ADMIN_A_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_LAST_ACTIVE_ADMIN));

        when(userRepository.findActiveSystemAdminIdsForUpdate()).thenReturn(List.of());
        assertThatThrownBy(() -> service.updateUser(
            ADMIN_B_ID,
            new UpdateUserRequest(null, null, null, null, false),
            ADMIN_A_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_LAST_ACTIVE_ADMIN));

        // 4. Another admin can be deactivated when >= 2 active admins exist
        when(userRepository.findActiveSystemAdminIdsForUpdate()).thenReturn(List.of(ADMIN_A_ID, ADMIN_B_ID));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse deactivated = service.updateUser(
            ADMIN_B_ID,
            new UpdateUserRequest(null, null, null, null, false),
            ADMIN_A_ID
        );
        assertThat(deactivated.isActive()).isFalse();
        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(ADMIN_B_ID), any(OffsetDateTime.class));
        verify(tokenBlacklistService).revokeAllUserTokens(ADMIN_B_ID, Duration.ofMinutes(15));
    }

    @Test
    void updateUserRevokesTokensOnlyOnSecuritySensitiveChangesAndResetsPasswordWithRevocation() {
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT", "IT");
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR", "HR");
        UserEntity target = user(USER_ID, hrDept, "emp@documind.ai", "Old Name", Role.STANDARD_EMPLOYEE, true);

        when(userRepository.findWithDepartmentById(USER_ID)).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Updating fullName only does NOT revoke active JWT sessions
        AdminUserResponse renamed = service.updateUser(
            USER_ID,
            new UpdateUserRequest(null, "New Name", null, null, null),
            ADMIN_A_ID
        );
        assertThat(renamed.fullName()).isEqualTo("New Name");
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any(), any());
        verifyNoInteractions(tokenBlacklistService);

        // 2. Changing departmentId / role revokes refresh tokens AND writes Redis user cutoff
        when(departmentRepository.findById(IT_DEPT_ID)).thenReturn(Optional.of(itDept));
        AdminUserResponse transferred = service.updateUser(
            USER_ID,
            new UpdateUserRequest(null, null, Role.DEPARTMENT_MANAGER, IT_DEPT_ID, null),
            ADMIN_A_ID
        );
        assertThat(transferred.role()).isEqualTo(Role.DEPARTMENT_MANAGER);
        assertThat(transferred.departmentId()).isEqualTo(IT_DEPT_ID);
        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(USER_ID), any(OffsetDateTime.class));
        verify(tokenBlacklistService).revokeAllUserTokens(USER_ID, Duration.ofMinutes(15));

        // 3. Reset password hashes new password and revokes all active tokens
        service.resetPassword(USER_ID, new AdminResetPasswordRequest("NewStrongPass@2026!"), ADMIN_A_ID);
        assertThat(passwordEncoder.matches("NewStrongPass@2026!", target.getPasswordHash())).isTrue();
        verify(refreshTokenRepository, org.mockito.Mockito.times(2))
            .revokeAllActiveByUserId(eq(USER_ID), any(OffsetDateTime.class));
        verify(tokenBlacklistService, org.mockito.Mockito.times(2))
            .revokeAllUserTokens(USER_ID, Duration.ofMinutes(15));
    }

    @Test
    void rejectsPasswordsOver72Utf8BytesAndMalformedEmailsAndMisclassifiedFkRaceAndRedisSplitBrain() {
        DepartmentEntity hrDept = department(HR_DEPT_ID, "HR", "Human Resources");

        // F-04: 73 ASCII bytes and multi-byte Vietnamese >72 UTF-8 bytes rejected with 400 (never BCrypt 500)
        String seventyThreeAscii = "a".repeat(73);
        String vietnameseOver72Bytes = "MậtKhẩuTiếngViệtCóDấuSiêuDàiNăm2026!".repeat(2);
        assertThat(vietnameseOver72Bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(72);

        assertThatThrownBy(() -> service.createUser(
            new CreateUserRequest("ok@documind.ai", "User", seventyThreeAscii, Role.STANDARD_EMPLOYEE, HR_DEPT_ID, true),
            ADMIN_A_ID
        )).isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.resetPassword(
            USER_ID, new AdminResetPasswordRequest(vietnameseOver72Bytes), ADMIN_A_ID
        )).isInstanceOf(InvalidAdminRequestException.class);

        // F-06: Malformed emails with spaces, double-@, or missing TLD rejected in service layer too
        for (String badEmail : List.of("a@@b.com", "bad email@documind.ai", "user@localhost")) {
            assertThatThrownBy(() -> service.createUser(
                new CreateUserRequest(badEmail, "User", "ValidPass@2026!", Role.STANDARD_EMPLOYEE, HR_DEPT_ID, true),
                ADMIN_A_ID
            )).isInstanceOf(InvalidAdminRequestException.class);
        }

        // F-07: Concurrent department deletion FK failure maps to 404 ResourceNotFoundException, NOT DUPLICATE_EMAIL
        when(userRepository.existsByEmailIgnoreCase("fk.race@documind.ai")).thenReturn(false);
        when(departmentRepository.findById(HR_DEPT_ID)).thenReturn(Optional.of(hrDept));
        when(userRepository.saveAndFlush(any(UserEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("fk_users_department"));
        when(departmentRepository.existsById(HR_DEPT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.createUser(
            new CreateUserRequest("fk.race@documind.ai", "Race User", "ValidPass@2026!", Role.STANDARD_EMPLOYEE, HR_DEPT_ID, true),
            ADMIN_A_ID
        )).isInstanceOf(ResourceNotFoundException.class);

        // F-01: Redis unavailability during deactivation rolls back DB transaction atomically
        DepartmentEntity itDept = department(IT_DEPT_ID, "IT", "IT");
        UserEntity activeAdmin = user(ADMIN_A_ID, itDept, "admin@documind.ai", "Admin A", Role.SYSTEM_ADMIN, true);
        StubTransactionManager trackingTxManager = new StubTransactionManager();
        AdminUserService txTrackedService = new AdminUserService(
            userRepository, departmentRepository, refreshTokenRepository,
            trackingTxManager, passwordEncoder, tokenBlacklistService, jwtService,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
        UserEntity activeTarget = user(USER_ID, hrDept, "active@documind.ai", "Active User", Role.STANDARD_EMPLOYEE, true);
        org.mockito.Mockito.reset(userRepository, tokenBlacklistService);
        when(userRepository.findById(ADMIN_A_ID)).thenReturn(Optional.of(activeAdmin));
        when(userRepository.findWithDepartmentById(USER_ID)).thenReturn(Optional.of(activeTarget));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new com.documind.exception.AuthStateStoreUnavailableException("Redis timeout"))
            .when(tokenBlacklistService).revokeAllUserTokens(USER_ID, Duration.ofMinutes(15));

        assertThatThrownBy(() -> txTrackedService.updateUser(
            USER_ID, new UpdateUserRequest(null, null, null, null, false), ADMIN_A_ID
        )).isInstanceOf(com.documind.exception.AuthStateStoreUnavailableException.class);
        assertThat(trackingTxManager.rollbackCount).isEqualTo(1);
        assertThat(trackingTxManager.commitCount).isEqualTo(0);
        verify(userRepository).lockActorTargetAndActiveAdminsForUpdate(ADMIN_A_ID, USER_ID);

        // F-01: Redis unavailability during resetPassword also rolls back DB transaction atomically
        assertThatThrownBy(() -> txTrackedService.resetPassword(
            USER_ID, new AdminResetPasswordRequest("ValidPass@2026!"), ADMIN_A_ID
        )).isInstanceOf(com.documind.exception.AuthStateStoreUnavailableException.class);
        assertThat(trackingTxManager.rollbackCount).isEqualTo(2);
        assertThat(trackingTxManager.commitCount).isEqualTo(0);
        verify(userRepository).lockUsersInOrderForUpdate(ADMIN_A_ID, USER_ID);

        // F-01: Idempotent deactivation retry on already-inactive user still enforces Redis revocation
        org.mockito.Mockito.reset(tokenBlacklistService);
        activeTarget.setIsActive(false);
        txTrackedService.updateUser(USER_ID, new UpdateUserRequest(null, null, null, null, false), ADMIN_A_ID);
        verify(tokenBlacklistService).revokeAllUserTokens(USER_ID, Duration.ofMinutes(15));

        // F-01: Deactivated or demoted admin actor blocked by DB verification
        activeAdmin.setIsActive(false);
        assertThatThrownBy(() -> txTrackedService.getUser(USER_ID, ADMIN_A_ID))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> txTrackedService.listUsers(null, null, null, null, 0, 20, ADMIN_A_ID))
            .isInstanceOf(AccessDeniedException.class);
    }

    private static DepartmentEntity department(UUID id, String code, String name) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(id);
        dept.setCode(code);
        dept.setName(name);
        dept.setCacheVersion(1L);
        return dept;
    }

    private static UserEntity user(UUID id, DepartmentEntity dept, String email, String fullName, Role role, boolean active) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDepartment(dept);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setIsActive(active);
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {
        int commitCount;
        int rollbackCount;

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            rollbackCount++;
        }
    }
}
