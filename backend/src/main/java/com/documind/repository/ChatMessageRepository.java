package com.documind.repository;

import com.documind.domain.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ChatMessageEntity} conversation turns and recent history windows.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<ChatMessageEntity> findTop6BySessionIdOrderByCreatedAtDesc(UUID sessionId);
}
