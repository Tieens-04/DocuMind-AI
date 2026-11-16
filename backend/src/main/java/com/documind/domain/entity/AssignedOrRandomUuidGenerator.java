package com.documind.domain.entity;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;
import org.springframework.data.domain.Persistable;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Hibernate 6 {@link BeforeExecutionGenerator} that preserves pre-assigned {@link UUID} identifiers
 * and generates {@link UUID#randomUUID()} when unassigned.
 */
public final class AssignedOrRandomUuidGenerator implements BeforeExecutionGenerator {

    @Override
    public Object generate(
        SharedSessionContractImplementor session,
        Object owner,
        Object currentValue,
        EventType eventType
    ) {
        if (currentValue instanceof UUID existingId) {
            return existingId;
        }
        if (owner instanceof Persistable<?> persistable && persistable.getId() instanceof UUID ownerId) {
            return ownerId;
        }
        return UUID.randomUUID();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }

    @Override
    public boolean allowAssignedIdentifiers() {
        return true;
    }
}
