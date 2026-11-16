package com.documind.repository;

import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DocumentEntity} metadata, deduplication checks, and filtered pagination.
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID>, JpaSpecificationExecutor<DocumentEntity> {

    boolean existsByDepartmentIdAndFileHash(UUID departmentId, String fileHash);

    Optional<DocumentEntity> findByDepartmentIdAndFileHash(UUID departmentId, String fileHash);

    List<DocumentEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId);

    /**
     * Queries documents applying predicates only for non-null filter parameters so PostgreSQL/Hibernate 6
     * never sees untyped {@code NULL} bind parameters.
     */
    default Page<DocumentEntity> findAllFiltered(
        UUID departmentId,
        DocumentStatus status,
        AccessLevel accessLevel,
        Pageable pageable
    ) {
        Specification<DocumentEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("departmentId"), departmentId));
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
