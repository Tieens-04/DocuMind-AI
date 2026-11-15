package com.documind.infrastructure.db;

import com.documind.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "spring.flyway.schemas=prod_spring_verify,public",
    "spring.flyway.default-schema=prod_spring_verify",
    "spring.jpa.properties.hibernate.default_schema=prod_spring_verify",
    "spring.ai.vectorstore.pgvector.schema-name=prod_spring_verify"
})
@ActiveProfiles("prod")
@Import(PostgresTestcontainersConfig.class)
class ProdProfileFlywayIsolationIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void prodProfileBootsWithHibernateAndPgVectorValidationExcludingDevSeedAndEnforcingLowercaseUniqueness() {
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.data.redis.repositories.enabled", Boolean.class)).isFalse();

        List<String> prodVersions = jdbcTemplate.queryForList(
            "SELECT version FROM prod_spring_verify.flyway_schema_history WHERE success = true AND version IS NOT NULL AND version <> '0' ORDER BY installed_rank",
            String.class
        );
        assertThat(prodVersions).containsExactly("1", "2");

        Integer prodUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_spring_verify.users", Integer.class);
        Integer prodDepartments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_spring_verify.departments", Integer.class);
        assertThat(prodUsers).isZero();
        assertThat(prodDepartments).isZero();

        jdbcTemplate.update("""
            INSERT INTO prod_spring_verify.departments (id, code, name)
            VALUES ('44444444-4444-4444-4444-444444444444', 'SEC', 'Security')
            """);
        try {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO prod_spring_verify.departments (code, name)
                VALUES ('sec', 'Duplicate Lowercase Security')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM prod_spring_verify.departments WHERE id = '44444444-4444-4444-4444-444444444444'");
        }
    }
}
