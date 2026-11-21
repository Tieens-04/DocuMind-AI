package com.documind.repository;

import com.documind.domain.entity.ChatSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ChatSessionEntity} ownership-scoped queries.
 */
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, UUID> {

    List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Page<ChatSessionEntity> findByUserId(UUID userId, Pageable pageable);

    Optional<ChatSessionEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ChatSessionEntity s WHERE s.id = :id AND s.userId = :userId")
    Optional<ChatSessionEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
}
