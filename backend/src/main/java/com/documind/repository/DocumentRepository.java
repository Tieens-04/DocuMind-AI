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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    boolean existsByIdAndCurrentVersionAndStatus(UUID id, Integer currentVersion, DocumentStatus status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DocumentEntity d
        SET d.updatedAt = CURRENT_TIMESTAMP
        WHERE d.id = :id
          AND d.currentVersion = :version
          AND d.status = com.documind.domain.enums.DocumentStatus.PROCESSING
        """)
    int touchProcessingHeartbeat(@Param("id") UUID id, @Param("version") int version);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DocumentEntity d
        SET d.status = com.documind.domain.enums.DocumentStatus.PROCESSING,
            d.chunkCount = 0,
            d.errorMessage = NULL
        WHERE d.id = :id
          AND d.currentVersion = :version
          AND d.status = com.documind.domain.enums.DocumentStatus.PENDING
        """)
    int tryMarkProcessing(@Param("id") UUID id, @Param("version") int version);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DocumentEntity d
        SET d.status = com.documind.domain.enums.DocumentStatus.COMPLETED,
            d.chunkCount = :chunkCount,
            d.errorMessage = NULL
        WHERE d.id = :id
          AND d.currentVersion = :version
          AND d.status = com.documind.domain.enums.DocumentStatus.PROCESSING
        """)
    int markCompleted(@Param("id") UUID id, @Param("version") int version, @Param("chunkCount") int chunkCount);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DocumentEntity d
        SET d.status = com.documind.domain.enums.DocumentStatus.FAILED,
            d.chunkCount = 0,
            d.errorMessage = :errorMessage
        WHERE d.id = :id
          AND d.currentVersion = :version
          AND d.status IN (
              com.documind.domain.enums.DocumentStatus.PENDING,
              com.documind.domain.enums.DocumentStatus.PROCESSING
          )
        """)
    int markFailed(@Param("id") UUID id, @Param("version") int version, @Param("errorMessage") String errorMessage);

    @Query("""
        SELECT d
        FROM DocumentEntity d
        WHERE d.status = com.documind.domain.enums.DocumentStatus.PROCESSING
          AND d.updatedAt < :cutoff
        ORDER BY d.updatedAt ASC
        """)
    List<DocumentEntity> findStaleProcessingBefore(@Param("cutoff") OffsetDateTime cutoff);

    @Query("""
        SELECT d
        FROM DocumentEntity d
        WHERE d.status = com.documind.domain.enums.DocumentStatus.PENDING
          AND d.updatedAt < :cutoff
        ORDER BY d.createdAt ASC
        """)
    List<DocumentEntity> findStalePendingBefore(@Param("cutoff") OffsetDateTime cutoff);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DocumentEntity d
        SET d.status = com.documind.domain.enums.DocumentStatus.FAILED,
            d.chunkCount = 0,
            d.errorMessage = :errorMessage
        WHERE d.id = :id
          AND d.currentVersion = :version
          AND d.status = com.documind.domain.enums.DocumentStatus.PROCESSING
          AND d.updatedAt < :cutoff
        """)
    int markStaleProcessingFailed(
        @Param("id") UUID id,
        @Param("version") int version,
        @Param("cutoff") OffsetDateTime cutoff,
        @Param("errorMessage") String errorMessage
    );

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
