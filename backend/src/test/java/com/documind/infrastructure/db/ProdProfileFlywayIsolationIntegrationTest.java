package com.documind.infrastructure.db;

import com.documind.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("prod")
@Import(ProdProfileFlywayIsolationIntegrationTest.IsolatedProdPostgresConfig.class)
class ProdProfileFlywayIsolationIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void prodProfileBootsCleanlyWithoutFlywayOverridesExcludingDevSeedAndEnforcingHardenedConstraints() {
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.data.redis.repositories.enabled", Boolean.class)).isFalse();

        List<String> prodVersions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL AND version <> '0' ORDER BY installed_rank",
            String.class
        );
        assertThat(prodVersions).containsExactly("1", "2", "4");

        Integer prodUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        Integer prodDepartments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM departments", Integer.class);
        assertThat(prodUsers).isZero();
        assertThat(prodDepartments).isZero();

        jdbcTemplate.update("""
            INSERT INTO departments (id, code, name)
            VALUES ('44444444-4444-4444-4444-444444444444', 'SEC', 'Security')
            """);
        try {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO departments (code, name)
                VALUES ('sec', 'Duplicate Lowercase Security')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM departments WHERE id = '44444444-4444-4444-4444-444444444444'");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedProdPostgresConfig {

        @Bean(initMethod = "start", destroyMethod = "stop")
        @ServiceConnection
        PostgreSQLContainer<?> prodPostgresContainer() {
            return new PostgreSQLContainer<>(
                DockerImageName.parse(PostgresTestcontainersConfig.PGVECTOR_IMAGE)
                    .asCompatibleSubstituteFor("postgres")
            );
        }
    }
}
