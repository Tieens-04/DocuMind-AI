package com.documind.service.admin;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminDepartmentResponse;
import com.documind.controller.dto.admin.CreateDepartmentRequest;
import com.documind.controller.dto.admin.UpdateDepartmentRequest;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.exception.AdminConflictException;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Administrative CRUD service for enterprise departments restricted exclusively to active {@code SYSTEM_ADMIN} actors.
 */
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminDepartmentService {

    private static final Logger log = LoggerFactory.getLogger(AdminDepartmentService.class);
    private static final Pattern DEPARTMENT_CODE_PATTERN = Pattern.compile("^[A-Z0-9_-]{2,32}$");
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final PlatformTransactionManager transactionManager;

    public AdminDepartmentService(
        @Lazy DepartmentRepository departmentRepository,
        @Lazy UserRepository userRepository,
        @Lazy DocumentRepository documentRepository,
        @Lazy PlatformTransactionManager transactionManager
    ) {
        this.departmentRepository = Objects.requireNonNull(departmentRepository, "departmentRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    public AdminDepartmentResponse createDepartment(CreateDepartmentRequest request, UUID actorUserId) {
        if (request == null) {
            throw new InvalidAdminRequestException("Create department request must not be null");
        }
        String normalizedCode = normalizeAndValidateCode(request.code());
        String normalizedName = normalizeRequiredName(request.name());
        String normalizedDescription = normalizeOptionalDescription(request.description());

        AdminDepartmentResponse created;
        try {
            created = AdminServiceSupport.executeInTransaction(transactionManager, false, () -> {
                AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
                if (departmentRepository.findByCodeIgnoreCase(normalizedCode).isPresent()) {
                    throw new AdminConflictException(
                        AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE,
                        "A department with code '" + normalizedCode + "' already exists"
                    );
                }

                DepartmentEntity entity = new DepartmentEntity();
                entity.setCode(normalizedCode);
                entity.setName(normalizedName);
                entity.setDescription(normalizedDescription);
                entity.setCacheVersion(1L);

                DepartmentEntity saved = departmentRepository.saveAndFlush(entity);
                return AdminDepartmentResponse.fromEntity(saved);
            });
        } catch (DataIntegrityViolationException ex) {
            throw new AdminConflictException(
                AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE,
                "A department with code '" + normalizedCode + "' already exists",
                ex
            );
        }

        log.info(
            "SECURITY_AUDIT action=ADMIN_DEPT_CREATE actorUserId={} departmentId={} code={}",
            actorUserId,
            created.id(),
            created.code()
        );
        return created;
    }

    public PageResponse<AdminDepartmentResponse> listDepartments(String query, int page, int size, UUID actorUserId) {
        AdminServiceSupport.validatePaging(page, size);
        String normalizedQuery = AdminServiceSupport.normalizeSearchQuery(query);
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "code"));
            Page<AdminDepartmentResponse> result = departmentRepository
                .searchDepartments(normalizedQuery, pageable)
                .map(AdminDepartmentResponse::fromEntity);
            return PageResponse.from(result);
        });
    }

    public AdminDepartmentResponse getDepartment(UUID departmentId, UUID actorUserId) {
        if (departmentId == null) {
            throw new InvalidAdminRequestException("Department ID must not be null");
        }
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            DepartmentEntity entity = departmentRepository
                .findById(departmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));
            return AdminDepartmentResponse.fromEntity(entity);
        });
    }

    public AdminDepartmentResponse updateDepartment(
        UUID departmentId,
        UpdateDepartmentRequest request,
        UUID actorUserId
    ) {
        if (departmentId == null) {
            throw new InvalidAdminRequestException("Department ID must not be null");
        }
        if (request == null || (request.code() == null && request.name() == null && request.description() == null)) {
            throw new InvalidAdminRequestException(
                "At least one updatable field (code, name, description) must be provided"
            );
        }
        String normalizedCode = request.code() != null ? normalizeAndValidateCode(request.code()) : null;
        String normalizedName = request.name() != null ? normalizeRequiredName(request.name()) : null;
        boolean updateDescription = request.description() != null;
        String normalizedDescription = updateDescription ? normalizeOptionalDescription(request.description()) : null;

        AdminDepartmentResponse updated;
        try {
            updated = AdminServiceSupport.executeInTransaction(transactionManager, false, () -> {
                AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
                DepartmentEntity entity = departmentRepository.findByIdForUpdate(departmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));

                if (normalizedCode != null) {
                    if (departmentRepository.existsByCodeIgnoreCaseAndIdNot(normalizedCode, departmentId)) {
                        throw new AdminConflictException(
                            AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE,
                            "A department with code '" + normalizedCode + "' already exists"
                        );
                    }
                    entity.setCode(normalizedCode);
                }
                if (normalizedName != null) {
                    entity.setName(normalizedName);
                }
                if (updateDescription) {
                    entity.setDescription(normalizedDescription);
                }

                DepartmentEntity saved = departmentRepository.saveAndFlush(entity);
                return AdminDepartmentResponse.fromEntity(saved);
            });
        } catch (DataIntegrityViolationException ex) {
            throw new AdminConflictException(
                AdminConflictException.CODE_DUPLICATE_DEPARTMENT_CODE,
                "Department code conflicts with an existing department",
                ex
            );
        }

        log.info(
            "SECURITY_AUDIT action=ADMIN_DEPT_UPDATE actorUserId={} departmentId={} code={}",
            actorUserId,
            updated.id(),
            updated.code()
        );
        return updated;
    }

    public void deleteDepartment(UUID departmentId, UUID actorUserId) {
        if (departmentId == null) {
            throw new InvalidAdminRequestException("Department ID must not be null");
        }
        String deletedCode;
        try {
            deletedCode = AdminServiceSupport.executeInTransaction(transactionManager, false, () -> {
                AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
                DepartmentEntity entity = departmentRepository.findByIdForUpdate(departmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));

                if (userRepository.existsByDepartmentId(departmentId)
                    || documentRepository.existsByDepartmentId(departmentId)) {
                    throw new AdminConflictException(
                        AdminConflictException.CODE_DEPARTMENT_IN_USE,
                        "Cannot delete department while users or documents are still assigned to it"
                    );
                }

                String code = entity.getCode();
                departmentRepository.delete(entity);
                departmentRepository.flush();
                return code;
            });
        } catch (DataIntegrityViolationException ex) {
            throw new AdminConflictException(
                AdminConflictException.CODE_DEPARTMENT_IN_USE,
                "Cannot delete department while users or documents are still assigned to it",
                ex
            );
        }

        log.info(
            "SECURITY_AUDIT action=ADMIN_DEPT_DELETE actorUserId={} departmentId={} code={}",
            actorUserId,
            departmentId,
            deletedCode
        );
    }

    private static String normalizeAndValidateCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new InvalidAdminRequestException("Department code must not be blank");
        }
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        if (!DEPARTMENT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new InvalidAdminRequestException(
                "Department code must be 2 to 32 characters matching [A-Z0-9_-]"
            );
        }
        return normalized;
    }

    private static String normalizeRequiredName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidAdminRequestException("Department name must not be blank");
        }
        String normalized = Normalizer.normalize(rawName.trim(), Normalizer.Form.NFC);
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new InvalidAdminRequestException(
                "Department name must not exceed " + MAX_NAME_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptionalDescription(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(rawDescription.trim(), Normalizer.Form.NFC);
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidAdminRequestException(
                "Department description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters"
            );
        }
        return normalized;
    }
}
