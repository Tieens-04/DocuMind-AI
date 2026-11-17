package com.documind.service.ingestion;

import com.documind.domain.enums.AccessLevel;
import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import com.documind.exception.DocumentStorageException;
import com.documind.infrastructure.storage.MinioStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeDocumentExtractorTest {

    @TempDir
    Path tempDir;

    private MinioStorageService minioStorageService;
    private IngestionProperties properties;
    private PdfDocumentExtractor pdfExtractor;
    private DocxDocumentExtractor docxExtractor;
    private CompositeDocumentExtractor compositeExtractor;

    @BeforeEach
    void setUp() {
        minioStorageService = mock(MinioStorageService.class);
        properties = IngestionProperties.defaults();
        PlainTextSectionExtractor plainTextExtractor = new PlainTextSectionExtractor(properties);
        MarkdownSectionExtractor markdownExtractor = new MarkdownSectionExtractor(properties);
        pdfExtractor = new PdfDocumentExtractor(properties);
        docxExtractor = new DocxDocumentExtractor(properties, plainTextExtractor);
        compositeExtractor = new CompositeDocumentExtractor(
            minioStorageService,
            properties,
            pdfExtractor,
            docxExtractor,
            markdownExtractor,
            plainTextExtractor,
            tempDir
        );
    }

    @Test
    void pdfExtractionPreservesVietnameseDiacriticsExactPageNumbersAndSkipsBlankPages() throws IOException {
        byte[] pdfBytes = createVietnameseMultiPagePdf(
            List.of(
                "Quy chế nghỉ phép năm: 12 ngày có hưởng lương.",
                "",
                "Phụ lục chế độ bảo hiểm xã hội bắt buộc."
            )
        );

        List<ExtractedSegment> segments = compositeExtractor.extractFromStream(
            "so_tay.pdf",
            "pdf",
            new ByteArrayInputStream(pdfBytes)
        );

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).pageNumber()).isEqualTo(1);
        assertThat(segments.get(0).sectionHeading()).isNull();
        assertThat(segments.get(0).content()).contains("Quy chế nghỉ phép năm: 12 ngày có hưởng lương.");

        assertThat(segments.get(1).pageNumber()).isEqualTo(3);
        assertThat(segments.get(1).sectionHeading()).isNull();
        assertThat(segments.get(1).content()).contains("Phụ lục chế độ bảo hiểm xã hội bắt buộc.");

        assertTempDirIsClean();
    }

    @Test
    void pdfTier2AndTier3FallbacksPreservePageNumbersWithoutOcr() throws IOException {
        byte[] pdfBytes = createVietnameseMultiPagePdf(
            List.of(
                "Trang một: Quy định bảo mật thông tin.",
                "   ",
                "Trang ba: Quy trình xử lý sự cố."
            )
        );
        Path pdfFile = tempDir.resolve("fallback-sample.pdf");
        Files.write(pdfFile, pdfBytes);

        PdfDocumentExtractor throwingTier1Extractor = new PdfDocumentExtractor(
            properties,
            path -> {
                throw new IllegalStateException("Simulated Tier 1 crop failure");
            }
        );
        List<ExtractedSegment> tier2Segments = throwingTier1Extractor.extract(pdfFile, "fallback.pdf");
        assertThat(tier2Segments).hasSize(2);
        assertThat(tier2Segments.get(0).pageNumber()).isEqualTo(1);
        assertThat(tier2Segments.get(0).content()).contains("Trang một: Quy định bảo mật thông tin.");
        assertThat(tier2Segments.get(1).pageNumber()).isEqualTo(3);
        assertThat(tier2Segments.get(1).content()).contains("Trang ba: Quy trình xử lý sự cố.");

        PdfDocumentExtractor emptyTier1Extractor = new PdfDocumentExtractor(properties, path -> Map.of());
        List<ExtractedSegment> emptyRescued = emptyTier1Extractor.extract(pdfFile, "fallback.pdf");
        assertThat(emptyRescued).extracting(ExtractedSegment::pageNumber).containsExactly(1, 3);

        Map<Integer, String> tier3Pages = pdfExtractor.extractViaTikaPageSax(pdfFile, "fallback.pdf");
        assertThat(tier3Pages.keySet()).containsExactly(1, 3);
        assertThat(tier3Pages.get(1)).contains("Trang một");
        assertThat(tier3Pages.get(3)).contains("Trang ba");
    }

    @Test
    void pdfRejectsScannedImageOnlyAndEncryptedDocumentsAndLeavesZeroTempFiles() throws IOException {
        byte[] scannedPdf = createImageOnlyPdf();
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "scan.pdf",
            "pdf",
            new ByteArrayInputStream(scannedPdf)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT);

        byte[] encryptedPdf = createEncryptedPdf("Mật khẩu bảo mật");
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "secret.pdf",
            "pdf",
            new ByteArrayInputStream(encryptedPdf)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT);

        assertTempDirIsClean();
    }

    @Test
    void docxExtractsHierarchicalBreadcrumbsPipeTablesUnstyledFallbackAndRejectsZipBomb() throws IOException {
        byte[] styledDocx = createStyledDocxWithTable();
        List<ExtractedSegment> styledSegments = compositeExtractor.extractFromStream(
            "chinh_sach.docx",
            "docx",
            new ByteArrayInputStream(styledDocx)
        );

        assertThat(styledSegments).hasSize(2);
        assertThat(styledSegments).allSatisfy(s -> assertThat(s.pageNumber()).isNull());
        assertThat(styledSegments.get(0).sectionHeading()).isEqualTo("Chương 1: Nhân sự");
        assertThat(styledSegments.get(0).content()).contains("Tổng quan chính sách nhân sự toàn công ty.");

        assertThat(styledSegments.get(1).sectionHeading()).isEqualTo("Chương 1: Nhân sự > Điều 2: Phụ cấp");
        assertThat(styledSegments.get(1).content())
            .contains("Phụ cấp ăn trưa 1.000.000 VNĐ/tháng.")
            .contains("| Loại | Mức hưởng |")
            .contains("| Ăn trưa | 1.000.000 |");

        byte[] unstyledDocx = createUnstyledDocx(
            "CHƯƠNG I: VẬN HÀNH",
            "Nội dung quy trình vận hành hệ thống nội bộ."
        );
        List<ExtractedSegment> unstyledSegments = compositeExtractor.extractFromStream(
            "unstyled.docx",
            "docx",
            new ByteArrayInputStream(unstyledDocx)
        );
        assertThat(unstyledSegments).hasSize(1);
        assertThat(unstyledSegments.getFirst().sectionHeading()).isEqualTo("CHƯƠNG I: VẬN HÀNH");
        assertThat(unstyledSegments.getFirst().content()).contains("Nội dung quy trình vận hành hệ thống nội bộ.");

        byte[] zipBombDocx = createHighlyCompressibleDocx(300_000);
        assertThatThrownBy(() -> compositeExtractor.extractFromStream(
            "bomb.docx",
            "docx",
            new ByteArrayInputStream(zipBombDocx)
        ))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);

        IngestionProperties tightCharCap = new IngestionProperties(
            new IngestionProperties.ExtractionProperties(52_428_800L, 100, 0.01d, 104_857_600L, 1_000L),
            IngestionProperties.ChunkingProperties.defaults()
        );
        DocxDocumentExtractor charCappedDocx = new DocxDocumentExtractor(
            tightCharCap,
            new PlainTextSectionExtractor(tightCharCap)
        );
        Path styledTemp = Files.write(tempDir.resolve("char-cap.docx"), styledDocx);
        assertThatThrownBy(() -> charCappedDocx.extract(styledTemp, "chinh_sach.docx"))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED);
        Files.deleteIfExists(styledTemp);

        assertTempDirIsClean();
    }

    @Test
    void compositeExtractsFromMinioClosesStreamsPropagatesStorageErrorsAndRejectsUnsupportedFormats() throws IOException {
        byte[] pdfBytes = createVietnameseMultiPagePdf(List.of("Tài liệu từ MinIO PDF."));
        byte[] docxBytes = createStyledDocxWithTable();
        byte[] mdBytes = "# Kiến trúc\n\nNội dung Markdown tiếng Việt.".getBytes(StandardCharsets.UTF_8);
        byte[] txtBytes = "CHƯƠNG I: NỘI QUY\n\nNội dung TXT tiếng Việt.".getBytes(StandardCharsets.UTF_8);

        assertMinioRoundTrip("doc.pdf", "pdf", pdfBytes, 1, null);
        assertMinioRoundTrip("doc.docx", "docx", docxBytes, null, "Chương 1: Nhân sự");
        assertMinioRoundTrip("doc.md", "md", mdBytes, null, "Kiến trúc");
        assertMinioRoundTrip("doc.txt", "txt", txtBytes, null, "CHƯƠNG I: NỘI QUY");

        DocumentIngestionContext unsupportedCtx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/1/upload/v1/sheet.xlsx",
            "sheet.xlsx",
            "xlsx",
            AccessLevel.DEPARTMENT,
            1
        );
        assertThatThrownBy(() -> compositeExtractor.extract(unsupportedCtx))
            .isInstanceOf(DocumentExtractionException.class)
            .extracting(ex -> ((DocumentExtractionException) ex).errorCode())
            .isEqualTo(DocumentExtractionErrorCode.UNSUPPORTED_FORMAT);

        DocumentIngestionContext missingMinioCtx = new DocumentIngestionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "departments/1/upload/v1/missing.pdf",
            "missing.pdf",
            "pdf",
            AccessLevel.PUBLIC,
            1
        );
        when(minioStorageService.openStream(missingMinioCtx.storageKey()))
            .thenThrow(new DocumentStorageException("MinIO down", new RuntimeException("connection refused")));
        assertThatThrownBy(() -> compositeExtractor.extract(missingMinioCtx))
            .isInstanceOf(DocumentStorageException.class);

        assertTempDirIsClean();
    }

    private void assertMinioRoundTrip(
        String name,
        String ext,
        byte[] payload,
        Integer expectedFirstPage,
        String expectedFirstHeading
    ) {
        UUID docId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        String key = "departments/" + deptId + "/" + docId + "/v1/" + name;
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        InputStream tracked = new FilterInputStream(new ByteArrayInputStream(payload)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };
        ResponseInputStream<GetObjectResponse> s3Stream = new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(tracked)
        );
        when(minioStorageService.openStream(key)).thenReturn(s3Stream);

        DocumentIngestionContext ctx = new DocumentIngestionContext(
            docId, deptId, key, name, ext, AccessLevel.DEPARTMENT, 1
        );
        List<ExtractedSegment> segments = compositeExtractor.extract(ctx);

        assertThat(streamClosed).isTrue();
        assertThat(segments).isNotEmpty();
        assertThat(segments.getFirst().pageNumber()).isEqualTo(expectedFirstPage);
        assertThat(segments.getFirst().sectionHeading()).isEqualTo(expectedFirstHeading);
        verify(minioStorageService).openStream(key);
    }

    private void assertTempDirIsClean() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            List<Path> leftovers = entries
                .filter(path -> path.getFileName().toString().startsWith("documind-extract-"))
                .toList();
            assertThat(leftovers).isEmpty();
        }
    }

    private static byte[] createVietnameseMultiPagePdf(List<String> pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font = loadBundledUnicodeFont(doc);
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (pageText != null && !pageText.isBlank()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(pageText);
                        cs.endText();
                    }
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createImageOnlyPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            PDImageXObject xObject = LosslessFactory.createFromImage(doc, image);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(xObject, 50, 650, 64, 64);
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createEncryptedPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font = loadBundledUnicodeFont(doc);
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static PDType0Font loadBundledUnicodeFont(PDDocument doc) throws IOException {
        try (InputStream fontStream = Objects.requireNonNull(
            PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"),
            "Bundled LiberationSans-Regular.ttf not found"
        )) {
            return PDType0Font.load(doc, fontStream);
        }
    }

    private static byte[] createStyledDocxWithTable() throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>Chương 1: Nhân sự</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>Tổng quan chính sách nhân sự toàn công ty.</w:t></w:r></w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                  <w:r><w:t>Điều 2: Phụ cấp</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>Phụ cấp ăn trưa 1.000.000 VNĐ/tháng.</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Loại</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Mức hưởng</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Ăn trưa</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>1.000.000</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
            """;
        return createDocxPackage(documentXml, true);
    }

    private static byte[] createUnstyledDocx(String headingLine, String bodyLine) throws IOException {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                <w:p><w:r><w:t></w:t></w:r></w:p>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.formatted(headingLine, bodyLine);
        return createDocxPackage(documentXml, false);
    }

    private static byte[] createHighlyCompressibleDocx(int repeatedChars) throws IOException {
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body><w:p><w:r><w:t>"
            + "A".repeat(repeatedChars)
            + "</w:t></w:r></w:p></w:body></w:document>";
        return createDocxPackage(documentXml, false);
    }

    private static byte[] createDocxPackage(String documentXml, boolean includeStyles) throws IOException {
        String contentTypes = includeStyles
            ? """
              <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
              </Types>
              """
            : """
              <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
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
              <w:style w:type="paragraph" w:styleId="Heading1">
                <w:name w:val="heading 1"/>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading2">
                <w:name w:val="heading 2"/>
              </w:style>
            </w:styles>
            """;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml", contentTypes);
            writeZipEntry(zos, "_rels/.rels", rootRels);
            if (includeStyles) {
                writeZipEntry(zos, "word/_rels/document.xml.rels", docRels);
                writeZipEntry(zos, "word/styles.xml", stylesXml);
            }
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
