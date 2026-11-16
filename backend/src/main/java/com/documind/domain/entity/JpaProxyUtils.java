package com.documind.domain.entity;

import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Extracts persistent classes and {@link UUID} identifiers without initializing lazy {@link HibernateProxy} references.
 */
final class JpaProxyUtils {

    private JpaProxyUtils() {
    }

    static Class<?> effectiveClass(Object entity) {
        if (entity instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return entity.getClass();
    }

    static UUID extractId(Persistable<UUID> entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof HibernateProxy proxy) {
            return (UUID) proxy.getHibernateLazyInitializer().getIdentifier();
        }
        return entity.getId();
    }
}
