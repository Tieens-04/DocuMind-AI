package com.documind.infrastructure.ratelimit;

import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void defaultsMatchCanonicalQuotasAndResolvePerRoleAndOperation() {
        RateLimitProperties defaults = RateLimitProperties.defaults();
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.expirationBuffer()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.periodFor(RateLimitOperation.CHAT)).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.periodFor(RateLimitOperation.UPLOAD)).isEqualTo(Duration.ofMinutes(1));

        assertThat(defaults.limitFor(RateLimitOperation.CHAT, Role.STANDARD_EMPLOYEE)).isEqualTo(15);
        assertThat(defaults.limitFor(RateLimitOperation.CHAT, Role.DEPARTMENT_MANAGER)).isEqualTo(30);
        assertThat(defaults.limitFor(RateLimitOperation.CHAT, Role.SYSTEM_ADMIN)).isEqualTo(60);

        assertThat(defaults.limitFor(RateLimitOperation.UPLOAD, Role.DEPARTMENT_MANAGER)).isEqualTo(5);
        assertThat(defaults.limitFor(RateLimitOperation.UPLOAD, Role.SYSTEM_ADMIN)).isEqualTo(10);
        assertThatThrownBy(() -> defaults.limitFor(RateLimitOperation.UPLOAD, Role.STANDARD_EMPLOYEE))
            .isInstanceOf(DocumentAccessDeniedException.class);
    }

    @Test
    void rejectsOutOfRangePeriodsBuffersAndLimits() {
        assertThatThrownBy(() -> new RateLimitProperties(
            true,
            Duration.ofSeconds(-1),
            RateLimitProperties.ChatLimits.defaults(),
            RateLimitProperties.UploadLimits.defaults()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RateLimitProperties.ChatLimits(Duration.ofMillis(50), 15, 30, 60))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RateLimitProperties.ChatLimits(Duration.ofMinutes(1), 0, 30, 60))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RateLimitProperties.UploadLimits(Duration.ofMinutes(1), 5, 100_001))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
