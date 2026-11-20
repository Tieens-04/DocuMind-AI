package com.documind.repository;

import com.documind.domain.entity.DepartmentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DepartmentEntity} and RBAC cache-version tracking.
 */
public interface DepartmentRepository extends JpaRepository<DepartmentEntity, UUID> {

    @Query("SELECT d FROM DepartmentEntity d WHERE LOWER(d.code) = LOWER(:code)")
    Optional<DepartmentEntity> findByCodeIgnoreCase(@Param("code") String code);

    @Query("SELECT COUNT(d) > 0 FROM DepartmentEntity d WHERE LOWER(d.code) = LOWER(:code) AND d.id <> :id")
    boolean existsByCodeIgnoreCaseAndIdNot(@Param("code") String code, @Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DepartmentEntity d WHERE d.id = :id")
    Optional<DepartmentEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT d FROM DepartmentEntity d
        WHERE (:query IS NULL
            OR LOCATE(LOWER(CAST(:query AS string)), LOWER(d.code)) > 0
            OR LOCATE(LOWER(CAST(:query AS string)), LOWER(d.name)) > 0)
        """)
    Page<DepartmentEntity> searchDepartments(@Param("query") String query, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DepartmentEntity d SET d.cacheVersion = d.cacheVersion + 1 WHERE d.id = :id")
    int incrementCacheVersion(@Param("id") UUID id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            WITH locked AS (
                SELECT id FROM departments ORDER BY id FOR NO KEY UPDATE
            )
            UPDATE departments d
            SET cache_version = d.cache_version + 1
            FROM locked
            WHERE d.id = locked.id
            """,
        nativeQuery = true
    )
    int incrementAllCacheVersions();

    @Query("SELECT COALESCE(SUM(d.cacheVersion), 0) FROM DepartmentEntity d")
    long sumCacheVersions();
}
