package com.documind.repository;

import com.documind.domain.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DocumentEntity} metadata and deduplication checks.
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    boolean existsByDepartmentIdAndFileHash(UUID departmentId, String fileHash);

    List<DocumentEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId);
}
