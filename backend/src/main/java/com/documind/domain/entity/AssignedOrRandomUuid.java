package com.documind.domain.entity;

import org.hibernate.annotations.IdGeneratorType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a random {@link java.util.UUID} on insert while permitting caller-assigned UUIDs
 * (for example, pre-computed MinIO object storage paths before database persistence).
 */
@IdGeneratorType(AssignedOrRandomUuidGenerator.class)
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@interface AssignedOrRandomUuid {
}
