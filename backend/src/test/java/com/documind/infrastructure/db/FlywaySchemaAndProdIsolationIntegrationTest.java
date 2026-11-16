package com.documind.infrastructure.db;

import com.documind.support.PostgresTestcontainersConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
class FlywaySchemaAndProdIsolationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void appliesV1ToV4AllNineTablesAndSeededUsersWithBcryptHashes() {
        List<String> versions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
            String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4");

        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class);
        assertThat(tables).contains(
            "departments", "users", "refresh_tokens", "documents",
            "chat_sessions", "chat_messages", "audit_logs",
            "document_chunks", "semantic_cache");

        Integer deptCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM departments", Integer.class);
        assertThat(deptCount).isEqualTo(3);

        List<String> hashes = jdbcTemplate.queryForList("SELECT password_hash FROM users ORDER BY email", String.class);
        assertThat(hashes).hasSize(3).allSatisfy(h -> assertThat(h).startsWith("$2a$10$").hasSize(60));
    }

    @Test
    void rejectsInvalidCheckConstraintsOnUsersAndDocuments() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO users (department_id, email, password_hash, full_name, role)
            VALUES ('11111111-1111-1111-1111-111111111111', 'bad@documind.ai', 'hash', 'Bad Role', 'SUPERUSER')
            """)).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO documents (department_id, original_name, storage_key, file_extension, mime_type,
                                   file_size_bytes, file_hash, access_level, status)
            VALUES ('11111111-1111-1111-1111-111111111111', 'huge.pdf', 'it/huge.pdf', 'pdf', 'application/pdf',
                    52428801, repeat('a', 64), 'DEPARTMENT', 'PENDING')
            """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCaseInsensitiveUniquenessOnUserEmailsAndDepartmentCodes() {
        try {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (department_id, email, password_hash, full_name, role)
                VALUES ('11111111-1111-1111-1111-111111111111', 'Admin@DocuMind.ai', 'hash', 'Mixed Case Admin', 'SYSTEM_ADMIN')
                """)).isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO departments (code, name)
                VALUES ('hr', 'Duplicate Lowercase HR')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE email = 'Admin@DocuMind.ai'");
            jdbcTemplate.update("DELETE FROM departments WHERE code = 'hr'");
        }
    }

    @Test
    void enforcesUniqueDocumentStorageKeyAndAuditLogCacheConsistency() {
        jdbcTemplate.update("""
            INSERT INTO documents (id, department_id, original_name, storage_key, file_extension, mime_type,
                                   file_size_bytes, file_hash, access_level, status)
            VALUES ('99999999-9999-9999-9999-999999999991', '11111111-1111-1111-1111-111111111111',
                    'first.pdf', 'shared/object-key.pdf', 'pdf', 'application/pdf',
                    1024, repeat('1', 64), 'DEPARTMENT', 'PENDING')
            """);
        try {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO documents (id, department_id, original_name, storage_key, file_extension, mime_type,
                                       file_size_bytes, file_hash, access_level, status)
                VALUES ('99999999-9999-9999-9999-999999999992', '22222222-2222-2222-2222-222222222222',
                        'second.pdf', 'shared/object-key.pdf', 'pdf', 'application/pdf',
                        1024, repeat('2', 64), 'DEPARTMENT', 'PENDING')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM documents WHERE id IN ('99999999-9999-9999-9999-999999999991', '99999999-9999-9999-9999-999999999992')");
        }

        try {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO audit_logs (action_type, cache_hit, cache_tier)
                VALUES ('CHAT_QUERY', FALSE, 'L1')
                """)).isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO audit_logs (action_type, cache_hit, cache_tier)
                VALUES ('CHAT_QUERY', TRUE, 'NONE')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM audit_logs");
        }
    }

    @Test
    void prodFlywayLocationsExcludeSeedMigrationV3() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties prodProps = yaml.getObject();
        assertThat(prodProps).isNotNull();
        String prodLocations = prodProps.getProperty("spring.flyway.locations");
        assertThat(prodLocations).isEqualTo("classpath:db/migration");
        assertThat(String.valueOf(prodProps.get("spring.flyway.baseline-on-migrate"))).isEqualTo("false");

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS prod_verify");
        Flyway prodFlyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas("prod_verify")
            .defaultSchema("prod_verify")
            .baselineOnMigrate(false)
            .locations(prodLocations.split(","))
            .load();
        prodFlyway.migrate();

        List<String> prodVersions = jdbcTemplate.queryForList(
            "SELECT version FROM prod_verify.flyway_schema_history WHERE success = true AND version <> '0' ORDER BY installed_rank",
            String.class);
        assertThat(prodVersions).containsExactly("1", "2", "4");

        Integer prodUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_verify.users", Integer.class);
        assertThat(prodUsers).isZero();

        Integer prodDepartments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_verify.departments", Integer.class);
        assertThat(prodDepartments).isZero();
    }
}
