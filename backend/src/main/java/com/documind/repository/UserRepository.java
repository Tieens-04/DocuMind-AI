package com.documind.repository;

import com.documind.domain.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link UserEntity} authentication, admin management, and account lookups.
 */
public interface UserRepository extends JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {

    @EntityGraph(attributePaths = "department")
    @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

    @Override
    @EntityGraph(attributePaths = "department")
    Optional<UserEntity> findById(UUID id);

    @EntityGraph(attributePaths = "department")
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findWithDepartmentById(@Param("id") UUID id);

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email) AND u.id <> :id")
    boolean existsByEmailIgnoreCaseAndIdNot(@Param("email") String email, @Param("id") UUID id);

    boolean existsByDepartmentId(UUID departmentId);

    @Query(
        value = "SELECT id FROM users WHERE id = :id FOR UPDATE",
        nativeQuery = true
    )
    Optional<UUID> lockUserByIdForUpdate(@Param("id") UUID id);

    @Query(
        value = "SELECT id FROM users WHERE role = 'SYSTEM_ADMIN' AND is_active = TRUE ORDER BY id ASC FOR UPDATE",
        nativeQuery = true
    )
    List<UUID> findActiveSystemAdminIdsForUpdate();

    @Query(
        value = "SELECT id FROM users WHERE id IN (:actorId, :targetId) ORDER BY id ASC FOR UPDATE",
        nativeQuery = true
    )
    List<UUID> lockUsersInOrderForUpdate(@Param("actorId") UUID actorId, @Param("targetId") UUID targetId);

    @Query(
        value = """
            SELECT id FROM users
            WHERE id IN (:actorId, :targetId)
               OR (role = 'SYSTEM_ADMIN' AND is_active = TRUE)
            ORDER BY id ASC
            FOR UPDATE
            """,
        nativeQuery = true
    )
    List<UUID> lockActorTargetAndActiveAdminsForUpdate(
        @Param("actorId") UUID actorId,
        @Param("targetId") UUID targetId
    );

    @Override
    @EntityGraph(attributePaths = "department")
    Page<UserEntity> findAll(Specification<UserEntity> spec, Pageable pageable);
}
