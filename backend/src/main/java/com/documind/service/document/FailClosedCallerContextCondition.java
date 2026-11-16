package com.documind.service.document;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Inverse of {@link DevCallerFallbackEnabledCondition} ensuring unprofiled, staging,
 * test, and production environments fail closed by default.
 */
final class FailClosedCallerContextCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !DevCallerFallbackEnabledCondition.isDevFallbackActive(context.getEnvironment());
    }
}
