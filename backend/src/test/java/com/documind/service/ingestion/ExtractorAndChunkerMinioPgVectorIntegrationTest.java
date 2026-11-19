package com.documind.service.ingestion;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@Import({PostgresTestcontainersConfig.class, MinioTestcontainersConfig.class})
class ExtractorAndChunkerMinioPgVectorIntegrationTest {

    @Autowired
    private IngestionProperties ingestionProperties;

    @Autowired
    private CompositeDocumentExtractor compositeDocumentExtractor;

    @Autowired
    private SmartChunkingService smartChunkingService;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> uploadedStorageKeys = new ArrayList<>();

    @AfterEach
    void cleanUpTestState() {
        for (String key : uploadedStorageKeys) {
            minioStorageService.deleteObjectQuietly(key);
        }
        uploadedStorageKeys.clear();
        jdbcTemplate.update("DELETE FROM semantic_cache");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
    }

    @Test
    void extractsAndChunksAllFourVietnameseFormatsFromMinioIntoPgVectorSatisfyingV4V5TriggersAndIdempotency()
        throws IOException {
        assertThat(ingestionProperties.extraction().maxSpoolBytes()).isEqualTo(52_428_800L);
        assertThat(ingestionProperties.extraction().maxExtractedChars()).isEqualTo(5_000_000);
        assertThat(ingestionProperties.chunking().chunkSize()).isEqualTo(512);
        assertThat(ingestionProperties.chunking().chunkOverlap()).isEqualTo(100);
        assertThat(ingestionProperties.chunking().maxChunksPerDocument()).isEqualTo(10_000);

        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();

        byte[] pdfBytes = createThreePagePdfWithBlankMiddlePage();
        byte[] docxBytes = createStyledDocxBytes();
        byte[] mdBytes = """
            # Kiến trúc Hệ thống RAG

            ```java
            // # Comment trong code block không được tách thành section
            String model = "bge-m3";
            ```

            ## Tầng Truy xuất Vector

            Hệ thống DocuMind AI sử dụng HNSW trên PostgreSQL pgvector để truy xuất ngữ nghĩa chính xác.
            """.getBytes(StandardCharsets.UTF_8);
        byte[] txtBytes = """
            CHƯƠNG I: BẢO MẬT DỮ LIỆU

            Điều 1. Quy tắc truy cập nội bộ
            Nhân viên chỉ được phép tra cứu tài liệu thuộc phòng ban mình hoặc tài liệu công khai theo quy trình:

            1. Đăng nhập tài khoản doanh nghiệp hợp lệ
            2. Chọn đúng phòng ban hoặc tài liệu công khai được phân quyền
            3. Không sao chép dữ liệu mật ra thiết bị cá nhân
            """.getBytes(StandardCharsets.UTF_8);

        DocumentEntity pdfDoc = uploadAndPersistDocument(
            hr, hrManager,
            "Sổ tay Nhân sự #2026.pdf", "so_tay_nhan_su_2026.pdf", "pdf", "application/pdf",
            AccessLevel.DEPARTMENT, pdfBytes
        );
        DocumentEntity docxDoc = uploadAndPersistDocument(
            hr, hrManager,
            "Quy trình Phê duyệt Nghỉ phép.docx", "quy_trinh_nghi_phep.docx", "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            AccessLevel.DEPARTMENT, docxBytes
        );
        DocumentEntity mdDoc = uploadAndPersistDocument(
            hr, hrManager,
            "Kiến trúc Hệ thống RAG.md", "kien_truc_rag.md", "md", "text/markdown",
            AccessLevel.PUBLIC, mdBytes
        );
        DocumentEntity txtDoc = uploadAndPersistDocument(
            hr, hrManager,
            "Nội quy Bảo mật CNTT.txt", "noi_quy_bao_mat.txt", "txt", "text/plain",
            AccessLevel.DEPARTMENT, txtBytes
        );

        List<Document> pdfChunks = extractChunkAndStore(pdfDoc);
        List<Document> docxChunks = extractChunkAndStore(docxDoc);
        List<Document> mdChunks = extractChunkAndStore(mdDoc);
        List<Document> txtChunks = extractChunkAndStore(txtDoc);

        List<Map<String, Object>> pdfRows = jdbcTemplate.queryForList(
            """
            SELECT document_id, doc_version, chunk_index, page_number,
                   metadata->>'file_name' AS file_name,
                   metadata->>'section_heading' AS section_heading
            FROM document_chunks
            WHERE document_id = ?
            ORDER BY chunk_index
            """,
            pdfDoc.getId()
        );
        assertThat(pdfRows).hasSize(2);
        assertThat(pdfRows).extracting(row -> row.get("page_number")).containsExactly(1, 3);
        assertThat(pdfRows).extracting(row -> row.get("chunk_index")).containsExactly(0, 1);
        assertThat(pdfRows).allSatisfy(row -> {
            assertThat(row.get("doc_version")).isEqualTo(1);
            assertThat(row.get("file_name")).isEqualTo("Sổ tay Nhân sự #2026.pdf");
            assertThat(row.get("section_heading")).isNull();
        });

        assertNonPdfTriggerNormalizedRows(docxDoc, "Quy trình Phê duyệt Nghỉ phép.docx", docxChunks.size());
        assertNonPdfTriggerNormalizedRows(mdDoc, "Kiến trúc Hệ thống RAG.md", mdChunks.size());
        assertNonPdfTriggerNormalizedRows(txtDoc, "Nội quy Bảo mật CNTT.txt", txtChunks.size());
        String persistedTxtContent = jdbcTemplate.queryForObject(
            "SELECT content FROM document_chunks WHERE document_id = ? AND chunk_index = 0",
            String.class,
            txtDoc.getId()
        );
        assertThat(persistedTxtContent)
            .contains("1. Đăng nhập tài khoản doanh nghiệp hợp lệ")
            .contains("2. Chọn đúng phòng ban hoặc tài liệu công khai được phân quyền")
            .contains("3. Không sao chép dữ liệu mật ra thiết bị cá nhân");

        // Prove DA-9 @Retryable idempotency: re-adding identical deterministic chunks succeeds without unique violations
        vectorStore.add(pdfChunks);
        vectorStore.add(docxChunks);

        Integer pdfCountAfterRetry = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
            Integer.class,
            pdfDoc.getId()
        );
        Integer docxCountAfterRetry = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
            Integer.class,
            docxDoc.getId()
        );
        assertThat(pdfCountAfterRetry).isEqualTo(pdfChunks.size());
        assertThat(docxCountAfterRetry).isEqualTo(docxChunks.size());

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression hrEmployeeFilter = builder.or(
            builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, "PUBLIC"),
            builder.and(
                builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, hr.getId().toString()),
                builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, "DEPARTMENT")
            )
        ).build();

        List<Document> pdfSearchHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(pdfChunks.getFirst().getText())
                .topK(5)
                .similarityThreshold(0.95d)
                .filterExpression(hrEmployeeFilter)
                .build()
        );
        assertThat(pdfSearchHits).isNotEmpty();
        Document topPdfHit = pdfSearchHits.getFirst();
        assertThat(topPdfHit.getMetadata().get(ChunkMetadataKeys.FILE_NAME)).isEqualTo("Sổ tay Nhân sự #2026.pdf");
        assertThat(topPdfHit.getMetadata().get(ChunkMetadataKeys.PAGE_NUMBER)).isEqualTo(1);
        assertThat(topPdfHit.getMetadata().get(ChunkMetadataKeys.CHUNK_INDEX)).isEqualTo(0);

        List<Document> docxSearchHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(docxChunks.getFirst().getText())
                .topK(5)
                .similarityThreshold(0.95d)
                .filterExpression(hrEmployeeFilter)
                .build()
        );
        assertThat(docxSearchHits).isNotEmpty();
        Document topDocxHit = docxSearchHits.getFirst();
        assertThat(topDocxHit.getMetadata().get(ChunkMetadataKeys.FILE_NAME))
            .isEqualTo("Quy trình Phê duyệt Nghỉ phép.docx");
        assertThat(topDocxHit.getMetadata().get(ChunkMetadataKeys.SECTION_HEADING))
            .asString()
            .isNotBlank();
        assertThat(topDocxHit.getMetadata()).doesNotContainKey(ChunkMetadataKeys.PAGE_NUMBER);

        List<Document> mdSearchHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(mdChunks.getFirst().getText())
                .topK(5)
                .similarityThreshold(0.95d)
                .filterExpression(hrEmployeeFilter)
                .build()
        );
        assertThat(mdSearchHits).isNotEmpty();
        assertThat(mdSearchHits.getFirst().getMetadata().get(ChunkMetadataKeys.FILE_NAME))
            .isEqualTo("Kiến trúc Hệ thống RAG.md");
        assertThat(mdSearchHits.getFirst().getMetadata()).doesNotContainKey(ChunkMetadataKeys.PAGE_NUMBER);

        List<Document> txtSearchHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(txtChunks.getFirst().getText())
                .topK(5)
                .similarityThreshold(0.95d)
                .filterExpression(hrEmployeeFilter)
                .build()
        );
        assertThat(txtSearchHits).isNotEmpty();
        assertThat(txtSearchHits.getFirst().getMetadata().get(ChunkMetadataKeys.FILE_NAME))
            .isEqualTo("Nội quy Bảo mật CNTT.txt");
        assertThat(txtSearchHits.getFirst().getMetadata()).doesNotContainKey(ChunkMetadataKeys.PAGE_NUMBER);
    }

    private void assertNonPdfTriggerNormalizedRows(DocumentEntity doc, String expectedFileName, int expectedChunks) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT doc_version, chunk_index, page_number,
                   jsonb_exists(metadata, 'page_number') AS has_page_key,
                   metadata->>'file_name' AS file_name,
                   metadata->>'section_heading' AS section_heading,
                   content
            FROM document_chunks
            WHERE document_id = ?
            ORDER BY chunk_index
            """,
            doc.getId()
        );
        assertThat(rows).hasSize(expectedChunks);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            assertThat(row.get("doc_version")).isEqualTo(1);
            assertThat(row.get("chunk_index")).isEqualTo(i);
            assertThat(row.get("page_number")).isNull();
            assertThat(row.get("has_page_key")).isEqualTo(Boolean.FALSE);
            assertThat(row.get("file_name")).isEqualTo(expectedFileName);
            String heading = (String) row.get("section_heading");
            assertThat(heading).isNotBlank();
            assertThat((String) row.get("content")).startsWith(heading + "\n\n");
        }
    }

    private List<Document> extractChunkAndStore(DocumentEntity entity) {
        DocumentUploadedEvent event = new DocumentUploadedEvent(
            entity.getId(),
            entity.getDepartmentId(),
            entity.getStorageKey(),
            entity.getOriginalName(),
            entity.getFileExtension(),
            entity.getAccessLevel(),
            entity.getCurrentVersion()
        );
        DocumentIngestionContext ctx = DocumentIngestionContext.from(event);
        List<ExtractedSegment> segments = compositeDocumentExtractor.extract(ctx);
        List<Document> chunks = smartChunkingService.chunk(ctx, segments);
        vectorStore.add(chunks);
        return chunks;
    }

    private DocumentEntity uploadAndPersistDocument(
        DepartmentEntity dept,
        UserEntity uploader,
        String originalName,
        String sanitizedFileName,
        String extension,
        String mimeType,
        AccessLevel accessLevel,
        byte[] content
    ) {
        String storageKey = minioStorageService.buildStorageKey(
            dept.getId(),
            UUID.randomUUID(),
            1,
            sanitizedFileName
        );
        minioStorageService.uploadStream(storageKey, new ByteArrayInputStream(content), content.length, mimeType);
        uploadedStorageKeys.add(storageKey);

        DocumentEntity entity = new DocumentEntity();
        entity.setDepartment(dept);
        entity.setUploadedBy(uploader);
        entity.setOriginalName(originalName);
        entity.setStorageKey(storageKey);
        entity.setFileExtension(extension);
        entity.setMimeType(mimeType);
        entity.setFileSizeBytes((long) content.length);
        entity.setFileHash(sha256Hex(content));
        entity.setAccessLevel(accessLevel);
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setCurrentVersion(1);
        return documentRepository.saveAndFlush(entity);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static byte[] createThreePagePdfWithBlankMiddlePage() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font = loadBundledUnicodeFont(doc);
            writePdfPage(doc, font, "Trang 1: Quy định nghỉ phép năm cho nhân viên chính thức.");
            doc.addPage(new PDPage());
            writePdfPage(doc, font, "Trang 3: Chế độ phúc lợi và bảo hiểm sức khỏe nội bộ.");
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static void writePdfPage(PDDocument doc, PDType0Font font, String text) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(50, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    private static PDType0Font loadBundledUnicodeFont(PDDocument doc) throws IOException {
        try (InputStream fontStream = Objects.requireNonNull(
            PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
        )) {
            return PDType0Font.load(doc, fontStream);
        }
    }

    private static byte[] createStyledDocxBytes() throws IOException {
        String contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            </Types>
            """;
        String rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;
        String docRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>
            """;
        String stylesXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
              <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/></w:style>
            </w:styles>
            """;
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Chương 1: Nghỉ phép</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                  <w:r><w:t>Điều 1: Phê duyệt trực tuyến</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>Quản lý bộ phận phê duyệt đơn nghỉ phép trong vòng 24 giờ làm việc.</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Loại phép</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Số ngày</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Phép năm</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>12 ngày</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
            """;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml", contentTypes);
            writeZipEntry(zos, "_rels/.rels", rootRels);
            writeZipEntry(zos, "word/_rels/document.xml.rels", docRels);
            writeZipEntry(zos, "word/styles.xml", stylesXml);
            writeZipEntry(zos, "word/document.xml", documentXml);
        }
        return baos.toByteArray();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String xml) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
