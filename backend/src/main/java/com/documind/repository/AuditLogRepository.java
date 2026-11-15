package com.documind.repository;

import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.enums.AuditActionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for paginated {@link AuditLogEntity} governance queries.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Page<AuditLogEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId, Pageable pageable);

    Page<AuditLogEntity> findByActionTypeOrderByCreatedAtDesc(AuditActionType actionType, Pageable pageable);
}
