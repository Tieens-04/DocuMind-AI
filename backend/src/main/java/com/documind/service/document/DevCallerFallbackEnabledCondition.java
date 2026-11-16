package com.documind.service.document;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring {@link Condition} enabling header-based dev caller fallback strictly when
 * {@code dev} or {@code local} profile is active, {@code prod} is inactive, and
 * {@code documind.security.dev-caller-fallback.enabled=true}.
 */
final class DevCallerFallbackEnabledCondition implements Condition {

    static final String PROPERTY_ENABLED = "documind.security.dev-caller-fallback.enabled";

    static boolean isDevFallbackActive(Environment env) {
        return env != null
            && !env.acceptsProfiles(Profiles.of("prod"))
            && env.acceptsProfiles(Profiles.of("dev", "local"))
            && Boolean.parseBoolean(env.getProperty(PROPERTY_ENABLED, "false"));
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isDevFallbackActive(context.getEnvironment());
    }
}
