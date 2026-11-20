package com.documind.service.admin;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminDepartmentResponse;
import com.documind.controller.dto.admin.CreateDepartmentRequest;
import com.documind.controller.dto.admin.UpdateDepartmentRequest;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.Role;
import com.documind.exception.AdminConflictException;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDepartmentServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DEPT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private DepartmentRepository departmentRepository;
    private UserRepository userRepository;
    private DocumentRepository documentRepository;
    private AdminDepartmentService service;

    @BeforeEach
    void setUp() {
        departmentRepository = mock(DepartmentRepository.class);
        userRepository = mock(UserRepository.class);
        documentRepository = mock(DocumentRepository.class);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(activeAdmin(ADMIN_ID)));
        service = new AdminDepartmentService(
            departmentRepository,
            userRepository,
            documentRepository,
            new StubTransactionManager()
        );
    }

    @Test
    void createDepartmentNormalizesCodeUppercaseAndVietnameseNfc() {
        String nfdName = Normalizer.normalize("Phòng Tài chính", Normalizer.Form.NFD);
        String nfcName = Normalizer.normalize("Phòng Tài chính", Normalizer.Form.NFC);
        when(departmentRepository.findByCodeIgnoreCase("FIN_01")).thenReturn(Optional.empty());
        when(departmentRepository.saveAndFlush(any(DepartmentEntity.class))).thenAnswer(inv -> {
            DepartmentEntity entity = inv.getArgument(0);
            entity.setId(DEPT_ID);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            return entity;
        });

        AdminDepartmentResponse created = service.createDepartment(
            new CreateDepartmentRequest("  fin_01  ", " " + nfdName + " ", "  Mô tả  "),
            ADMIN_ID
        );

        assertThat(created.id()).isEqualTo(DEPT_ID);
        assertThat(created.code()).isEqualTo("FIN_01");
        assertThat(created.name()).isEqualTo(nfcName);
        assertThat(created.description()).isEqualTo("Mô tả");
        assertThat(created.cacheVersion()).isEqualTo(1L);
    }

    @Test
    void createDepartmentRejectsInvalidCodeAndDuplicateCaseInsensitiveCode() {
        assertThatThrownBy(() -> service.createDepartment(
            new CreateDepartmentRequest("bad code!", "Finance", null),
            ADMIN_ID
        )).isInstanceOf(InvalidAdminRequestException.class);

        assertThatThrownBy(() -> service.createDepartment(
            new CreateDepartmentRequest("A", "Finance", null),
            ADMIN_ID
        )).isInstanceOf(InvalidAdminRequestException.class);

        DepartmentEntity existing = department(DEPT_ID, "FIN", "Finance");
        when(departmentRepository.findByCodeIgnoreCase("FIN")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createDepartment(
            new CreateDepartmentRequest("fin", "Finance Duplicate", null),
            ADMIN_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE));

        // Concurrent unique index race also maps cleanly to 409 DUPLICATE_DEPARTMENT_CODE
        when(departmentRepository.findByCodeIgnoreCase("LEGAL")).thenReturn(Optional.empty());
        when(departmentRepository.saveAndFlush(any(DepartmentEntity.class)))
            .thenThrow(new DataIntegrityViolationException("uq_departments_code_lower"));

        assertThatThrownBy(() -> service.createDepartment(
            new CreateDepartmentRequest("legal", "Legal", null),
            ADMIN_ID
        ))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE));
    }

    @Test
    void listAndGetDepartmentsValidatePagingAndSupportKeywordSearch() {
        assertThatThrownBy(() -> service.listDepartments(null, -1, 20, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.listDepartments(null, 0, 0, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.listDepartments(null, 0, 101, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);

        DepartmentEntity dept = department(DEPT_ID, "FIN", "Tài chính");
        when(departmentRepository.searchDepartments(eq("fin"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(dept), PageRequest.of(0, 20), 1));
        when(departmentRepository.searchDepartments(isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(dept), PageRequest.of(0, 20), 1));

        PageResponse<AdminDepartmentResponse> filtered = service.listDepartments("  fin  ", 0, 20, ADMIN_ID);
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().getFirst().code()).isEqualTo("FIN");

        PageResponse<AdminDepartmentResponse> unfiltered = service.listDepartments("   ", 0, 20, ADMIN_ID);
        assertThat(unfiltered.totalElements()).isEqualTo(1L);

        when(departmentRepository.findById(DEPT_ID)).thenReturn(Optional.of(dept));
        assertThat(service.getDepartment(DEPT_ID, ADMIN_ID).code()).isEqualTo("FIN");

        UUID missing = UUID.randomUUID();
        when(departmentRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDepartment(missing, ADMIN_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateDepartmentRejectsEmptyPayloadChecksDuplicateCodeAndUpdatesFields() {
        assertThatThrownBy(() -> service.updateDepartment(DEPT_ID, new UpdateDepartmentRequest(null, null, null), ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);

        DepartmentEntity dept = department(DEPT_ID, "FIN", "Finance");
        dept.setDescription("Initial finance description");
        when(departmentRepository.findByIdForUpdate(DEPT_ID)).thenReturn(Optional.of(dept));
        when(departmentRepository.existsByCodeIgnoreCaseAndIdNot("HR", DEPT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateDepartment(DEPT_ID, new UpdateDepartmentRequest("hr", null, null), ADMIN_ID))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE));

        when(departmentRepository.existsByCodeIgnoreCaseAndIdNot("FIN-OPS", DEPT_ID)).thenReturn(false);
        when(departmentRepository.saveAndFlush(any(DepartmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Omitting description (null) preserves existing non-null description
        AdminDepartmentResponse renamedOnly = service.updateDepartment(
            DEPT_ID,
            new UpdateDepartmentRequest(null, "Tài chính Kế toán", null),
            ADMIN_ID
        );
        assertThat(renamedOnly.name()).isEqualTo("Tài chính Kế toán");
        assertThat(renamedOnly.description()).isEqualTo("Initial finance description");

        // Providing blank/empty description explicitly clears it to null
        AdminDepartmentResponse updated = service.updateDepartment(
            DEPT_ID,
            new UpdateDepartmentRequest("fin-ops", "Tài chính Vận hành", "   "),
            ADMIN_ID
        );

        assertThat(updated.code()).isEqualTo("FIN-OPS");
        assertThat(updated.name()).isEqualTo("Tài chính Vận hành");
        assertThat(updated.description()).isNull();
    }

    @Test
    void deleteDepartmentBlocksWhenReferencedByUsersOrDocumentsAndDeletesWhenUnused() {
        DepartmentEntity dept = department(DEPT_ID, "FIN", "Finance");
        when(departmentRepository.findByIdForUpdate(DEPT_ID)).thenReturn(Optional.of(dept));

        // 1. Referenced by users -> 409 DEPARTMENT_IN_USE
        when(userRepository.existsByDepartmentId(DEPT_ID)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteDepartment(DEPT_ID, ADMIN_ID))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DEPARTMENT_IN_USE));
        verify(departmentRepository, never()).delete(any());

        // 2. Referenced by documents -> 409 DEPARTMENT_IN_USE
        when(userRepository.existsByDepartmentId(DEPT_ID)).thenReturn(false);
        when(documentRepository.existsByDepartmentId(DEPT_ID)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteDepartment(DEPT_ID, ADMIN_ID))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DEPARTMENT_IN_USE));
        verify(departmentRepository, never()).delete(any());

        // 3. Concurrent FK violation race -> 409 DEPARTMENT_IN_USE
        when(documentRepository.existsByDepartmentId(DEPT_ID)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("fk_users_department"))
            .when(departmentRepository).flush();
        assertThatThrownBy(() -> service.deleteDepartment(DEPT_ID, ADMIN_ID))
            .isInstanceOf(AdminConflictException.class)
            .satisfies(ex -> assertThat(((AdminConflictException) ex).errorCode())
                .isEqualTo(AdminConflictException.CODE_DEPARTMENT_IN_USE));

        // 4. Unused department deletes cleanly
        org.mockito.Mockito.reset(departmentRepository);
        when(departmentRepository.findByIdForUpdate(DEPT_ID)).thenReturn(Optional.of(dept));
        service.deleteDepartment(DEPT_ID, ADMIN_ID);
        verify(departmentRepository).delete(dept);
        verify(departmentRepository).flush();
    }

    @Test
    void blocksInactiveOrDemotedAdminActorEvenWithoutRedisRevocation() {
        UserEntity inactiveAdmin = activeAdmin(ADMIN_ID);
        inactiveAdmin.setIsActive(false);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(inactiveAdmin));

        assertThatThrownBy(() -> service.listDepartments(null, 0, 20, ADMIN_ID))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getDepartment(DEPT_ID, ADMIN_ID))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.createDepartment(new CreateDepartmentRequest("OPS", "Ops", null), ADMIN_ID))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updateDepartment(DEPT_ID, new UpdateDepartmentRequest(null, "Ops", null), ADMIN_ID))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteDepartment(DEPT_ID, ADMIN_ID))
            .isInstanceOf(AccessDeniedException.class);

        UserEntity demotedAdmin = activeAdmin(ADMIN_ID);
        demotedAdmin.setRole(Role.DEPARTMENT_MANAGER);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(demotedAdmin));
        assertThatThrownBy(() -> service.listDepartments(null, 0, 20, ADMIN_ID))
            .isInstanceOf(AccessDeniedException.class);
    }

    private static UserEntity activeAdmin(UUID id) {
        UserEntity admin = new UserEntity();
        admin.setId(id);
        admin.setEmail("admin@documind.ai");
        admin.setFullName("System Administrator");
        admin.setRole(Role.SYSTEM_ADMIN);
        admin.setIsActive(true);
        return admin;
    }

    private static DepartmentEntity department(UUID id, String code, String name) {
        DepartmentEntity entity = new DepartmentEntity();
        entity.setId(id);
        entity.setCode(code);
        entity.setName(name);
        entity.setCacheVersion(1L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        }
    }
}
