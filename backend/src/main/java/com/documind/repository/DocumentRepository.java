package com.documind.repository;

import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DocumentEntity} metadata, deduplication checks, and RBAC-scoped pagination.
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID>, JpaSpecificationExecutor<DocumentEntity> {

    boolean existsByDepartmentIdAndFileHash(UUID departmentId, String fileHash);

    Optional<DocumentEntity> findByDepartmentIdAndFileHash(UUID departmentId, String fileHash);

    List<DocumentEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId);

    /**
     * Queries documents enforcing server-side role visibility:
     * {@code SYSTEM_ADMIN} sees all; {@code DEPARTMENT_MANAGER} sees {@code PUBLIC} or own department;
     * {@code STANDARD_EMPLOYEE} sees {@code PUBLIC} or own-department {@code DEPARTMENT} (never {@code CONFIDENTIAL}).
     */
    default Page<DocumentEntity> findAllVisible(
        UUID callerDepartmentId,
        Role callerRole,
        UUID filterDepartmentId,
        DocumentStatus status,
        AccessLevel accessLevel,
        Pageable pageable
    ) {
        Objects.requireNonNull(callerRole, "callerRole must not be null");
        if (callerRole != Role.SYSTEM_ADMIN) {
            Objects.requireNonNull(callerDepartmentId, "callerDepartmentId must not be null for non-admin query");
        }
        Specification<DocumentEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            switch (callerRole) {
                case SYSTEM_ADMIN -> {
                    // Full governance visibility across departments and classifications
                }
                case DEPARTMENT_MANAGER -> predicates.add(cb.or(
                    cb.equal(root.get("accessLevel"), AccessLevel.PUBLIC),
                    cb.equal(root.get("departmentId"), callerDepartmentId)
                ));
                case STANDARD_EMPLOYEE -> predicates.add(cb.or(
                    cb.equal(root.get("accessLevel"), AccessLevel.PUBLIC),
                    cb.and(
                        cb.equal(root.get("departmentId"), callerDepartmentId),
                        cb.equal(root.get("accessLevel"), AccessLevel.DEPARTMENT)
                    )
                ));
            }
            if (filterDepartmentId != null) {
                predicates.add(cb.equal(root.get("departmentId"), filterDepartmentId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (accessLevel != null) {
                predicates.add(cb.equal(root.get("accessLevel"), accessLevel));
            }
            return predicates.isEmpty() ? null : cb.and(predicates.toArray(Predicate[]::new));
        };
        return findAll(spec, pageable);
    }
}
