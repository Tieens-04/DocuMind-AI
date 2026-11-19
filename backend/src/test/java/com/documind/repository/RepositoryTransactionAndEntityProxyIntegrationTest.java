package com.documind.repository;

import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.RefreshTokenEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.model.CitationPayload;
import com.documind.support.PostgresTestcontainersConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@Import(PostgresTestcontainersConfig.class)
class RepositoryTransactionAndEntityProxyIntegrationTest {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreCleanDatabaseState() {
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
    }

    @Test
    void modifyingRepositoryMethodsExecuteWithoutCallerTransaction() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        UserEntity admin = userRepository.findByEmailIgnoreCase("admin@documind.ai").orElseThrow();

        int singleUpdated = departmentRepository.incrementCacheVersion(hr.getId());
        assertThat(singleUpdated).isEqualTo(1);
        assertThat(departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getCacheVersion()).isEqualTo(2L);

        int allUpdated = departmentRepository.incrementAllCacheVersions();
        assertThat(allUpdated).isEqualTo(3);
        assertThat(departmentRepository.sumCacheVersions()).isEqualTo(7L);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RefreshTokenEntity activeToken = new RefreshTokenEntity();
        activeToken.setUser(admin);
        activeToken.setTokenHash("7".repeat(64));
        activeToken.setExpiresAt(now.plusDays(7));
        refreshTokenRepository.saveAndFlush(activeToken);

        int revoked = refreshTokenRepository.revokeAllActiveByUserId(admin.getId(), now);
        assertThat(revoked).isEqualTo(1);
        assertThat(refreshTokenRepository.findByTokenHash("7".repeat(64)).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void preAssignedEntityUuidPersistsCleanlyAndLazyProxyReferenceStaysUninitialized() {
        UUID hrId = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getId();
        UUID hrManagerId = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow().getId();
        UUID preAssignedDocId = UUID.randomUUID();
        PersistenceUnitUtil unitUtil = entityManagerFactory.getPersistenceUnitUtil();

        DocumentEntity saved = transactionTemplate.execute(status -> {
            DepartmentEntity deptProxy = departmentRepository.getReferenceById(hrId);
            UserEntity managerProxy = userRepository.getReferenceById(hrManagerId);
            assertThat(unitUtil.isLoaded(deptProxy)).isFalse();
            assertThat(unitUtil.isLoaded(managerProxy)).isFalse();

            DocumentEntity doc = new DocumentEntity();
            doc.setId(preAssignedDocId);
            doc.setDepartment(deptProxy);
            doc.setUploadedBy(managerProxy);

            // Setting lazy proxy references or comparing equality must NOT trigger lazy SQL initialization
            assertThat(unitUtil.isLoaded(deptProxy)).isFalse();
            assertThat(unitUtil.isLoaded(managerProxy)).isFalse();
            assertThat(doc.getDepartmentId()).isEqualTo(hrId);
            assertThat(doc.getUploadedById()).isEqualTo(hrManagerId);

            DepartmentEntity detachedSameId = new DepartmentEntity();
            detachedSameId.setId(hrId);
            assertThat(detachedSameId).isEqualTo(deptProxy);
            assertThat(unitUtil.isLoaded(deptProxy)).isFalse();

            doc.setOriginalName("preassigned-minio-key.pdf");
            doc.setStorageKey(hrId + "/" + preAssignedDocId + "/preassigned-minio-key.pdf");
            doc.setFileExtension("pdf");
            doc.setMimeType("application/pdf");
            doc.setFileSizeBytes(8192L);
            doc.setFileHash("8".repeat(64));
            doc.setAccessLevel(AccessLevel.DEPARTMENT);
            doc.setStatus(DocumentStatus.PENDING);

            return documentRepository.saveAndFlush(doc);
        });

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(preAssignedDocId);

        DocumentEntity reloaded = documentRepository.findById(preAssignedDocId).orElseThrow();
        reloaded.setStatus(DocumentStatus.COMPLETED);
        reloaded.setChunkCount(5);
        DocumentEntity updated = documentRepository.saveAndFlush(reloaded);
        assertThat(updated.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(updated.getChunkCount()).isEqualTo(5);
    }

    @Test
    void auditLogAutoSyncsCacheHitFromCacheTierAndCitationPayloadHasExplicitJsonProperties() {
        UserEntity admin = userRepository.findByEmailIgnoreCase("admin@documind.ai").orElseThrow();

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setUser(admin);
        auditLog.setDepartment(admin.getDepartment());
        auditLog.setActionType(AuditActionType.CHAT_QUERY);
        auditLog.setQueryText("Kiểm tra đồng bộ cacheHit từ cacheTier");
        auditLog.setCacheTier(CacheTier.L1);

        AuditLogEntity saved = auditLogRepository.saveAndFlush(auditLog);
        assertThat(saved.isCacheHit()).isTrue();
        assertThat(saved.getCacheTier()).isEqualTo(CacheTier.L1);

        AuditLogEntity staleFalseWithL2 = new AuditLogEntity();
        staleFalseWithL2.setUser(admin);
        staleFalseWithL2.setDepartment(admin.getDepartment());
        staleFalseWithL2.setActionType(AuditActionType.CHAT_QUERY);
        staleFalseWithL2.setQueryText("Kiểm tra @PrePersist đồng bộ L2");
        staleFalseWithL2.setCacheTier(CacheTier.L2);
        staleFalseWithL2.setCacheHit(Boolean.FALSE);
        AuditLogEntity savedL2 = auditLogRepository.saveAndFlush(staleFalseWithL2);
        assertThat(savedL2.isCacheHit()).isTrue();
        assertThat(savedL2.getCacheTier()).isEqualTo(CacheTier.L2);

        AuditLogEntity staleTrueWithNone = new AuditLogEntity();
        staleTrueWithNone.setUser(admin);
        staleTrueWithNone.setDepartment(admin.getDepartment());
        staleTrueWithNone.setActionType(AuditActionType.CHAT_QUERY);
        staleTrueWithNone.setQueryText("Kiểm tra @PrePersist đồng bộ NONE");
        staleTrueWithNone.setCacheTier(CacheTier.NONE);
        staleTrueWithNone.setCacheHit(Boolean.TRUE);
        AuditLogEntity savedNone = auditLogRepository.saveAndFlush(staleTrueWithNone);
        assertThat(savedNone.isCacheHit()).isFalse();
        assertThat(savedNone.getCacheTier()).isEqualTo(CacheTier.NONE);

        List<String> jsonPropertyNames = Arrays.stream(CitationPayload.class.getRecordComponents())
            .map(rc -> {
                JsonProperty ann = rc.getAccessor().getAnnotation(JsonProperty.class);
                return ann != null ? ann.value() : null;
            })
            .toList();
        assertThat(jsonPropertyNames).containsExactly(
            "documentId", "fileName", "pageNumber", "sectionHeading", "similarityScore", "excerpt"
        );
    }
}
