package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.domain.model.CitationPayload;
import com.documind.exception.DocumentStorageException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.DocumentRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkMetadataKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.net.URL;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorRetrievalServiceTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private VectorStore vectorStore;
    private DocumentRepository documentRepository;
    private MinioStorageService minioStorageService;
    private RagProperties properties;
    private VectorRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        documentRepository = mock(DocumentRepository.class);
        minioStorageService = mock(MinioStorageService.class);
        properties = RagProperties.defaults();
        retrievalService = new VectorRetrievalService(
            providerOf(vectorStore),
            providerOf(documentRepository),
            minioStorageService,
            properties
        );
    }

    @Test
    void ragPropertiesDefaultsAndBoundsValidation() {
        RagProperties defaults = RagProperties.defaults();
        assertThat(defaults.retrieval().topK()).isEqualTo(5);
        assertThat(defaults.retrieval().similarityThreshold()).isEqualTo(0.58d);
        assertThat(defaults.retrieval().overfetchMultiplier()).isEqualTo(2);
        assertThat(defaults.retrieval().maxExcerptChars()).isEqualTo(500);
        assertThat(defaults.retrieval().candidateFetchLimit()).isEqualTo(10);
        assertThat(defaults.llm().maxQuestionChars()).isEqualTo(4000);
        assertThat(defaults.llm().fallbackEnabled()).isTrue();

        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(0, 0.65d, 2, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(21, 0.65d, 2, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(5, -0.01d, 2, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(5, 1.01d, 2, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(5, Double.NaN, 2, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(5, 0.65d, 0, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(5, 0.65d, 6, 500))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.RetrievalProperties(5, 0.65d, 2, 49))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.LlmProperties(9, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildRbacFilterEnforcesRoleMatrixAndRejectsMissingDepartmentForNonAdmin() {
        CallerContext employee = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        Filter.Expression employeeFilter = retrievalService.buildRbacFilter(employee);
        assertThat(employeeFilter.type()).isEqualTo(Filter.ExpressionType.OR);
        assertThat(employeeFilter.left()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
            new Filter.Value("PUBLIC")
        ));
        assertThat(employeeFilter.right()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.AND,
            new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(ChunkMetadataKeys.DEPARTMENT_ID),
                new Filter.Value(HR_DEPT_ID.toString())
            ),
            new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
                new Filter.Value("DEPARTMENT")
            )
        ));

        CallerContext manager = new CallerContext(USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");
        Filter.Expression managerFilter = retrievalService.buildRbacFilter(manager);
        assertThat(managerFilter.type()).isEqualTo(Filter.ExpressionType.OR);
        assertThat(managerFilter.left()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL),
            new Filter.Value("PUBLIC")
        ));
        assertThat(managerFilter.right()).isEqualTo(new Filter.Expression(
            Filter.ExpressionType.EQ,
            new Filter.Key(ChunkMetadataKeys.DEPARTMENT_ID),
            new Filter.Value(HR_DEPT_ID.toString())
        ));

        CallerContext adminWithoutDept = new CallerContext(USER_ID, null, Role.SYSTEM_ADMIN, "127.0.0.1");
        Filter.Expression adminFilter = retrievalService.buildRbacFilter(adminWithoutDept);
        assertThat(adminFilter.type()).isEqualTo(Filter.ExpressionType.IN);
        assertThat(adminFilter.left()).isEqualTo(new Filter.Key(ChunkMetadataKeys.ACCESS_LEVEL));
        assertThat(adminFilter.right()).isEqualTo(new Filter.Value(List.of("PUBLIC", "DEPARTMENT", "CONFIDENTIAL")));

        assertThatThrownBy(() -> retrievalService.buildRbacFilter(null))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> retrievalService.buildRbacFilter(new CallerContext(USER_ID, HR_DEPT_ID, null, null)))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> retrievalService.buildRbacFilter(new CallerContext(USER_ID, null, Role.STANDARD_EMPLOYEE, null)))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> retrievalService.buildRbacFilter(new CallerContext(USER_ID, null, Role.DEPARTMENT_MANAGER, null)))
            .isInstanceOf(InvalidChatRequestException.class);
    }

    @Test
    void retrieveNormalizesNfcCallsVectorStoreOnceAndFiltersOutProcessingStaleAndUnauthorizedChunks() throws Exception {
        CallerContext hrEmployee = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        UUID validPdfId = UUID.randomUUID();
        UUID processingDocId = UUID.randomUUID();
        UUID staleDocId = UUID.randomUUID();
        UUID confidentialDocId = UUID.randomUUID();
        UUID publicMdId = UUID.randomUUID();

        Document validPdfChunk = chunk(
            validPdfId, HR_DEPT_ID, AccessLevel.DEPARTMENT, "hr-handbook.pdf", 2, 0, 3, null, 0.91d,
            "Nhân viên chính thức được hưởng 12 ngày nghỉ phép nguyên lương mỗi năm."
        );
        Document processingChunk = chunk(
            processingDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, "draft-policy.pdf", 1, 0, 1, null, 0.95d,
            "Bản nháp đang xử lý chưa hoàn tất."
        );
        Document staleVersionChunk = chunk(
            staleDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, "updated-policy.pdf", 1, 0, 2, null, 0.89d,
            "Nội dung phiên bản v1 đã lỗi thời."
        );
        Document leakedConfidentialChunk = chunk(
            confidentialDocId, HR_DEPT_ID, AccessLevel.CONFIDENTIAL, "salary-secret.pdf", 1, 0, 1, null, 0.93d,
            "Thông tin lương bí mật chỉ dành cho quản lý."
        );
        Document publicMdChunk = chunk(
            publicMdId, IT_DEPT_ID, AccessLevel.PUBLIC, "remote-policy.md", 1, 1, null, "Hybrid Policy", 0.78d,
            "Nhân viên có thể làm việc từ xa tối đa 2 ngày mỗi tuần."
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(List.of(processingChunk, leakedConfidentialChunk, validPdfChunk, staleVersionChunk, publicMdChunk));

        DocumentEntity validPdfEntity = docEntity(validPdfId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, 2, "hr-handbook.pdf", "departments/hr/1/v2/hr-handbook.pdf");
        DocumentEntity processingEntity = docEntity(processingDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.PROCESSING, 1, "draft-policy.pdf", "departments/hr/2/v1/draft-policy.pdf");
        DocumentEntity staleEntity = docEntity(staleDocId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, 2, "updated-policy.pdf", "departments/hr/3/v2/updated-policy.pdf");
        DocumentEntity confidentialEntity = docEntity(confidentialDocId, HR_DEPT_ID, AccessLevel.CONFIDENTIAL, DocumentStatus.COMPLETED, 1, "salary-secret.pdf", "departments/hr/4/v1/salary-secret.pdf");
        DocumentEntity publicMdEntity = docEntity(publicMdId, IT_DEPT_ID, AccessLevel.PUBLIC, DocumentStatus.COMPLETED, 1, "remote-policy.md", "departments/it/5/v1/remote-policy.md");

        when(documentRepository.findAllById(anyIterable()))
            .thenReturn(List.of(validPdfEntity, processingEntity, staleEntity, confidentialEntity, publicMdEntity));

        URL pdfSignedUrl = URI.create("http://localhost:9000/documind-documents/hr-handbook.pdf?X-Amz-Signature=abc").toURL();
        URL mdSignedUrl = URI.create("http://localhost:9000/documind-documents/remote-policy.md?X-Amz-Signature=xyz").toURL();
        when(minioStorageService.generatePresignedGetUrl("departments/hr/1/v2/hr-handbook.pdf", "hr-handbook.pdf"))
            .thenReturn(pdfSignedUrl);
        when(minioStorageService.generatePresignedGetUrl("departments/it/5/v1/remote-policy.md", "remote-policy.md"))
            .thenReturn(mdSignedUrl);

        String nfdQuestion = Normalizer.normalize("  Quy định nghỉ phép năm?  ", Normalizer.Form.NFD);
        List<RetrievedChunk> results = retrievalService.retrieve(nfdQuestion, hrEmployee);

        ArgumentCaptor<SearchRequest> searchCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(searchCaptor.capture());
        SearchRequest capturedRequest = searchCaptor.getValue();
        assertThat(Normalizer.isNormalized(capturedRequest.getQuery(), Normalizer.Form.NFC)).isTrue();
        assertThat(capturedRequest.getQuery()).isEqualTo("Quy định nghỉ phép năm?");
        assertThat(capturedRequest.getTopK()).isEqualTo(10);
        assertThat(capturedRequest.getSimilarityThreshold()).isEqualTo(0.58d);
        assertThat(capturedRequest.getFilterExpression()).isEqualTo(retrievalService.buildRbacFilter(hrEmployee));

        verify(documentRepository, times(1)).findAllById(anyIterable());
        assertThat(results).hasSize(2);

        RetrievedChunk first = results.get(0);
        assertThat(first.documentId()).isEqualTo(validPdfId);
        assertThat(first.citation().fileName()).isEqualTo("hr-handbook.pdf");
        assertThat(first.citation().pageNumber()).isEqualTo(3);
        assertThat(first.citation().similarityScore()).isEqualTo(0.91d);
        assertThat(first.citation().presignedUrl()).isEqualTo(pdfSignedUrl + "#page=3");

        RetrievedChunk second = results.get(1);
        assertThat(second.documentId()).isEqualTo(publicMdId);
        assertThat(second.citation().fileName()).isEqualTo("remote-policy.md");
        assertThat(second.citation().pageNumber()).isNull();
        assertThat(second.citation().sectionHeading()).isEqualTo("Hybrid Policy");
        assertThat(second.citation().presignedUrl()).isEqualTo(mdSignedUrl.toString());

        verify(minioStorageService, times(1)).generatePresignedGetUrl("departments/hr/1/v2/hr-handbook.pdf", "hr-handbook.pdf");
        verify(minioStorageService, times(1)).generatePresignedGetUrl("departments/it/5/v1/remote-policy.md", "remote-policy.md");
    }

    @Test
    void retrieveRejectsBlankOrNullByteQuestionsWithoutCallingVectorStore() {
        CallerContext hrEmployee = new CallerContext(USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        assertThatThrownBy(() -> retrievalService.retrieve("   \n\t ", hrEmployee))
            .isInstanceOf(InvalidChatRequestException.class);
        assertThatThrownBy(() -> retrievalService.retrieve("Câu hỏi\0độc hại", hrEmployee))
            .isInstanceOf(InvalidChatRequestException.class);
        verifyNoInteractions(vectorStore, documentRepository, minioStorageService);
    }

    @Test
    void retrieveCapsResultsToTopKAndMemoizesPresignedUrlPerDocument() throws Exception {
        RagProperties customProps = new RagProperties(
            new RagProperties.RetrievalProperties(2, 0.65d, 2, 500),
            new RagProperties.LlmProperties(4000, true)
        );
        VectorRetrievalService customService = new VectorRetrievalService(
            providerOf(vectorStore),
            providerOf(documentRepository),
            minioStorageService,
            customProps
        );
        CallerContext manager = new CallerContext(USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

        UUID doc1Id = UUID.randomUUID();
        UUID doc2Id = UUID.randomUUID();
        Document c1 = chunk(doc1Id, HR_DEPT_ID, AccessLevel.DEPARTMENT, "policy.pdf", 1, 0, 2, null, 0.94d, "Page 2 text");
        Document c2 = chunk(doc1Id, HR_DEPT_ID, AccessLevel.DEPARTMENT, "policy.pdf", 1, 1, 5, null, 0.87d, "Page 5 text");
        Document c3 = chunk(doc2Id, HR_DEPT_ID, AccessLevel.PUBLIC, "guide.pdf", 1, 0, 1, null, 0.75d, "Page 1 guide");

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(c1, c2, c3));
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(
            docEntity(doc1Id, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, 1, "policy.pdf", "departments/hr/1/v1/policy.pdf"),
            docEntity(doc2Id, IT_DEPT_ID, AccessLevel.PUBLIC, DocumentStatus.COMPLETED, 1, "guide.pdf", "departments/it/2/v1/guide.pdf")
        ));

        URL doc1Url = URI.create("http://localhost:9000/documind-documents/policy.pdf?sig=1").toURL();
        when(minioStorageService.generatePresignedGetUrl("departments/hr/1/v1/policy.pdf", "policy.pdf"))
            .thenReturn(doc1Url);

        List<RetrievedChunk> hits = customService.retrieve("Quy định nghỉ phép", manager);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).citation().pageNumber()).isEqualTo(2);
        assertThat(hits.get(0).citation().presignedUrl()).isEqualTo(doc1Url + "#page=2");
        assertThat(hits.get(1).citation().pageNumber()).isEqualTo(5);
        assertThat(hits.get(1).citation().presignedUrl()).isEqualTo(doc1Url + "#page=5");
        verify(minioStorageService, times(1)).generatePresignedGetUrl(anyString(), anyString());
    }

    @Test
    void retrieveTruncatesExcerptSafelyAndHandlesPresignFailureGracefully() {
        RagProperties shortExcerptProps = new RagProperties(
            new RagProperties.RetrievalProperties(5, 0.65d, 2, 60),
            new RagProperties.LlmProperties(4000, true)
        );
        VectorRetrievalService service = new VectorRetrievalService(
            providerOf(vectorStore),
            providerOf(documentRepository),
            minioStorageService,
            shortExcerptProps
        );
        CallerContext manager = new CallerContext(USER_ID, HR_DEPT_ID, Role.DEPARTMENT_MANAGER, "127.0.0.1");

        UUID docId = UUID.randomUUID();
        String longText = "Quy định thưởng hiệu suất quý năm 2026 dành cho kỹ sư phần mềm \uD83D\uDE00 cùng các phụ cấp bổ sung.";
        Document hit = chunk(docId, HR_DEPT_ID, AccessLevel.DEPARTMENT, "bonus.pdf", 1, 0, 4, null, 0.88d, longText);

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));
        when(documentRepository.findAllById(anyIterable())).thenReturn(List.of(
            docEntity(docId, HR_DEPT_ID, AccessLevel.DEPARTMENT, DocumentStatus.COMPLETED, 1, "bonus.pdf", "departments/hr/9/v1/bonus.pdf")
        ));
        when(minioStorageService.generatePresignedGetUrl(anyString(), anyString()))
            .thenThrow(new DocumentStorageException("MinIO timeout", new RuntimeException("S3 timeout")));

        List<RetrievedChunk> results = service.retrieve("Thưởng hiệu suất", manager);
        assertThat(results).hasSize(1);
        CitationDto citation = results.getFirst().citation();
        assertThat(citation.presignedUrl()).isNull();
        assertThat(citation.excerpt()).endsWith("...");
        assertThat(citation.excerpt().length()).isLessThanOrEqualTo(60);
        for (int i = 0; i < citation.excerpt().length(); i++) {
            assertThat(Character.isSurrogate(citation.excerpt().charAt(i)))
                .as("No orphaned surrogate chars at index %d", i)
                .isFalse();
        }
    }

    @Test
    void citationDtoToCitationPayloadMapsStructuredFields() {
        UUID docId = UUID.randomUUID();
        CitationDto dto = new CitationDto(
            docId,
            "handbook.pdf",
            7,
            "Leave Policy",
            0.9123d,
            "Nhân viên được nghỉ 12 ngày phép.",
            "http://localhost:9000/documind-documents/handbook.pdf?sig=1#page=7"
        );
        CitationPayload payload = dto.toCitationPayload();
        assertThat(payload).isEqualTo(new CitationPayload(
            docId,
            "handbook.pdf",
            7,
            0.9123d,
            "Nhân viên được nghỉ 12 ngày phép."
        ));
    }

    private static Document chunk(
        UUID documentId,
        UUID departmentId,
        AccessLevel accessLevel,
        String fileName,
        int docVersion,
        int chunkIndex,
        Integer pageNumber,
        String sectionHeading,
        double score,
        String text
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.DOCUMENT_ID, documentId.toString());
        metadata.put(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString());
        metadata.put(ChunkMetadataKeys.ACCESS_LEVEL, accessLevel.name());
        metadata.put(ChunkMetadataKeys.FILE_NAME, fileName);
        metadata.put(ChunkMetadataKeys.DOC_VERSION, docVersion);
        metadata.put(ChunkMetadataKeys.CHUNK_INDEX, chunkIndex);
        if (pageNumber != null) {
            metadata.put(ChunkMetadataKeys.PAGE_NUMBER, pageNumber);
        }
        if (sectionHeading != null) {
            metadata.put(ChunkMetadataKeys.SECTION_HEADING, sectionHeading);
        }
        return Document.builder()
            .id(UUID.randomUUID().toString())
            .text(text)
            .metadata(metadata)
            .score(score)
            .build();
    }

    private static DocumentEntity docEntity(
        UUID id,
        UUID departmentId,
        AccessLevel accessLevel,
        DocumentStatus status,
        int currentVersion,
        String originalName,
        String storageKey
    ) {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(departmentId);
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setDepartment(dept);
        entity.setAccessLevel(accessLevel);
        entity.setStatus(status);
        entity.setCurrentVersion(currentVersion);
        entity.setOriginalName(originalName);
        entity.setStorageKey(storageKey);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
