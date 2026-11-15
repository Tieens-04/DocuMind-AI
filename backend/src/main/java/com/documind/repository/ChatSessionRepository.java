package com.documind.repository;

import com.documind.domain.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ChatSessionEntity} ownership-scoped queries.
 */
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, UUID> {

    List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<ChatSessionEntity> findByIdAndUserId(UUID id, UUID userId);
}
