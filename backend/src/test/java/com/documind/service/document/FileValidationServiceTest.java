package com.documind.service.document;

import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.InvalidDocumentRequestException;
import com.documind.exception.UnsupportedDocumentTypeException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileValidationServiceTest {

    private static final String DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private FileValidationService validator;

    @BeforeEach
    void setUp() {
        validator = new FileValidationService();
    }

    @Test
    void validPdfSucceedsAndRewindedStreamPreservesExactBytes() throws IOException {
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

        FileValidationResult result = validator.validateAndPrepareStream(
            "Quy-che-tai-chinh.PDF",
            pdfBytes.length,
            new ByteArrayInputStream(pdfBytes)
        );

        assertThat(result.originalName()).isEqualTo("Quy-che-tai-chinh.PDF");
        assertThat(result.sanitizedFileName()).isEqualTo("Quy-che-tai-chinh.pdf");
        assertThat(result.fileExtension()).isEqualTo("pdf");
        assertThat(result.canonicalMimeType()).isEqualTo("application/pdf");
        assertThat(result.declaredSizeBytes()).isEqualTo(pdfBytes.length);
        assertThat(result.rewindedStream().readAllBytes()).isEqualTo(pdfBytes);
    }

    @Test
    void validDocxSucceedsAndRewindedStreamPreservesExactBytes() throws IOException {
        byte[] docxBytes = createValidDocxBytes("<w:document><w:body><w:p><w:r><w:t>DocuMind</w:t></w:r></w:p></w:body></w:document>");

        FileValidationResult result = validator.validateAndPrepareStream(
            "Onboarding Guide.docx",
            docxBytes.length,
            new ByteArrayInputStream(docxBytes)
        );

        assertThat(result.originalName()).isEqualTo("Onboarding Guide.docx");
        assertThat(result.sanitizedFileName()).isEqualTo("Onboarding_Guide.docx");
        assertThat(result.fileExtension()).isEqualTo("docx");
        assertThat(result.canonicalMimeType()).isEqualTo(DOCX_MIME_TYPE);
        assertThat(result.declaredSizeBytes()).isEqualTo(docxBytes.length);
        assertThat(result.rewindedStream().readAllBytes()).isEqualTo(docxBytes);
    }

    @Test
    void validLargeDocxExceeding64KiBPrefixTruncatingMidEntrySucceedsAndPreservesAllBytes() throws IOException {
        byte[] largeDocxBytes = createLargeDocxExceeding64KiB();
        assertThat(largeDocxBytes.length).isGreaterThan(FileValidationService.HEADER_INSPECTION_BYTES * 2);

        FileValidationResult result = validator.validateAndPrepareStream(
            "Enterprise-Architecture-Handbook.docx",
            largeDocxBytes.length,
            new ByteArrayInputStream(largeDocxBytes)
        );

        assertThat(result.fileExtension()).isEqualTo("docx");
        assertThat(result.canonicalMimeType()).isEqualTo(DOCX_MIME_TYPE);
        assertThat(result.declaredSizeBytes()).isEqualTo(largeDocxBytes.length);
        assertThat(result.rewindedStream().readAllBytes()).isEqualTo(largeDocxBytes);
    }

    @Test
    void validVietnameseUtf8TxtSucceedsAndRewindedStreamPreservesExactBytes() throws IOException {
        byte[] txtBytes = "Tài liệu nội bộ phòng Nhân sự".getBytes(StandardCharsets.UTF_8);

        FileValidationResult result = validator.validateAndPrepareStream(
            "nhan-su.txt",
            txtBytes.length,
            new ByteArrayInputStream(txtBytes)
        );

        assertThat(result.originalName()).isEqualTo("nhan-su.txt");
        assertThat(result.sanitizedFileName()).isEqualTo("nhan-su.txt");
        assertThat(result.fileExtension()).isEqualTo("txt");
        assertThat(result.canonicalMimeType()).isEqualTo("text/plain");
        assertThat(result.declaredSizeBytes()).isEqualTo(txtBytes.length);
        assertThat(result.rewindedStream().readAllBytes()).isEqualTo(txtBytes);
    }

    @Test
    void validMarkdownSucceedsAndRewindedStreamPreservesExactBytes() throws IOException {
        byte[] mdBytes = "# Quy trình nghỉ phép\n- 12 ngày/năm".getBytes(StandardCharsets.UTF_8);

        FileValidationResult result = validator.validateAndPrepareStream(
            "nghi-phep.md",
            mdBytes.length,
            new ByteArrayInputStream(mdBytes)
        );

        assertThat(result.originalName()).isEqualTo("nghi-phep.md");
        assertThat(result.sanitizedFileName()).isEqualTo("nghi-phep.md");
        assertThat(result.fileExtension()).isEqualTo("md");
        assertThat(result.canonicalMimeType()).isEqualTo("text/markdown");
        assertThat(result.declaredSizeBytes()).isEqualTo(mdBytes.length);
        assertThat(result.rewindedStream().readAllBytes()).isEqualTo(mdBytes);
    }

    @Test
    void windowsPathNormalizesOriginalNameAndSanitizesUnsafeCharactersAndLength() throws IOException {
        byte[] pdfBytes = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        FileValidationResult result = validator.validateAndPrepareStream(
            "C:\\fakepath\\Báo cáo Q3 #1.pdf",
            pdfBytes.length,
            new ByteArrayInputStream(pdfBytes)
        );

        assertThat(result.originalName()).isEqualTo("Báo cáo Q3 #1.pdf");
        assertThat(result.sanitizedFileName())
            .isEqualTo("Báo_cáo_Q3_1.pdf")
            .doesNotContain("/", "\\", "#", "?", "%", " ", "..");

        String longStem = "A".repeat(220) + "..draft?%#";
        FileValidationResult longResult = validator.validateAndPrepareStream(
            "/var/uploads/" + longStem + ".docx",
            createValidDocxBytes("long").length,
            new ByteArrayInputStream(createValidDocxBytes("long"))
        );
        assertThat(longResult.originalName()).isEqualTo(longStem + ".docx");
        assertThat(longResult.sanitizedFileName().length()).isLessThanOrEqualTo(180);
        assertThat(longResult.sanitizedFileName())
            .endsWith(".docx")
            .doesNotContain("/", "\\", "#", "?", "%", " ", "..");
    }

    @Test
    void decomposedMacOsNfdFilenameIsNormalizedToPrecomposedNfc() throws IOException {
        byte[] pdfBytes = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        String expectedNfc = "Sổ tay nhân sự 2026.pdf";
        String macOsNfd = java.text.Normalizer.normalize(expectedNfc, java.text.Normalizer.Form.NFD);
        assertThat(macOsNfd).isNotEqualTo(expectedNfc);

        FileValidationResult result = validator.validateAndPrepareStream(
            macOsNfd,
            pdfBytes.length,
            new ByteArrayInputStream(pdfBytes)
        );

        assertThat(result.originalName()).isEqualTo(expectedNfc);
        assertThat(java.text.Normalizer.isNormalized(result.originalName(), java.text.Normalizer.Form.NFC)).isTrue();
        assertThat(java.text.Normalizer.isNormalized(result.sanitizedFileName(), java.text.Normalizer.Form.NFC)).isTrue();
    }

    @Test
    void rejectsZeroOrNegativeDeclaredSizeAndEmptyStreamWithInvalidDocumentRequestException() {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "empty.pdf",
            0L,
            new ByteArrayInputStream(pdfBytes)
        )).isInstanceOf(InvalidDocumentRequestException.class);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "negative.pdf",
            -5L,
            new ByteArrayInputStream(pdfBytes)
        )).isInstanceOf(InvalidDocumentRequestException.class);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "zero-bytes.pdf",
            128L,
            new ByteArrayInputStream(new byte[0])
        )).isInstanceOf(InvalidDocumentRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".pdf", "..pdf", "C:\\fakepath\\.pdf", "folder/"})
    void rejectsBlankOrDotOnlyHiddenFilenames(String invalidFilename) {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            invalidFilename,
            pdfBytes.length,
            new ByteArrayInputStream(pdfBytes)
        )).isInstanceOf(InvalidDocumentRequestException.class);
    }

    @Test
    void rejectsNullFilenameNullStreamAndOriginalNameOver255Chars() {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            null,
            pdfBytes.length,
            new ByteArrayInputStream(pdfBytes)
        )).isInstanceOf(InvalidDocumentRequestException.class);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "valid.pdf",
            pdfBytes.length,
            null
        )).isInstanceOf(InvalidDocumentRequestException.class);

        String tooLongName = "x".repeat(252) + ".pdf"; // 256 chars
        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            tooLongName,
            pdfBytes.length,
            new ByteArrayInputStream(pdfBytes)
        )).isInstanceOf(InvalidDocumentRequestException.class);
    }

    @Test
    void rejectsDeclaredSizeExceedingMaxFileSizeBytesWithDocumentTooLargeException() {
        byte[] pdfBytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        long oversized = HashingBoundedInputStream.MAX_FILE_SIZE_BYTES + 1L;

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "oversized.pdf",
            oversized,
            new ByteArrayInputStream(pdfBytes)
        ))
            .isInstanceOf(DocumentTooLargeException.class)
            .satisfies(ex -> assertThat(((DocumentTooLargeException) ex).maxSizeBytes())
                .isEqualTo(HashingBoundedInputStream.MAX_FILE_SIZE_BYTES));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malware.exe", "photo.png", "noext", "archive.zip", "trailing."})
    void rejectsDisallowedOrMissingExtensionsWithUnsupportedDocumentTypeException(String filename) {
        byte[] payload = "Hello world".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            filename,
            payload.length,
            new ByteArrayInputStream(payload)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void rejectsSpoofedPngMagicBytesNamedFakePdf() {
        byte[] pngMagic = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
        };

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "fake.pdf",
            pngMagic.length,
            new ByteArrayInputStream(pngMagic)
        ))
            .isInstanceOf(UnsupportedDocumentTypeException.class)
            .satisfies(ex -> assertThat(((UnsupportedDocumentTypeException) ex).detectedMimeType())
                .isEqualTo("image/png"));
    }

    @Test
    void rejectsPlainZipContainingOnlyPayloadExeNamedFakeDocx() throws IOException {
        byte[] plainZip = createCustomZipBytes(new String[]{"payload.exe"}, new byte[][]{"MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1)});

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "fake.docx",
            plainZip.length,
            new ByteArrayInputStream(plainZip)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void rejectsDocxContainingZipSlipTraversalEntry() throws IOException {
        byte[] zipSlipDocx = createCustomZipBytes(
            new String[]{"[Content_Types].xml", "word/document.xml", "../evil.xml"},
            new byte[][]{
                contentTypesXmlBytes(),
                "<w:document/>".getBytes(StandardCharsets.UTF_8),
                "<evil/>".getBytes(StandardCharsets.UTF_8)
            }
        );

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "slip.docx",
            zipSlipDocx.length,
            new ByteArrayInputStream(zipSlipDocx)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);

        byte[] nestedTraversalDocx = createCustomZipBytes(
            new String[]{"[Content_Types].xml", "word/document.xml", "word/../evil.xml"},
            new byte[][]{
                contentTypesXmlBytes(),
                "<w:document/>".getBytes(StandardCharsets.UTF_8),
                "<evil/>".getBytes(StandardCharsets.UTF_8)
            }
        );
        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "nested-slip.docx",
            nestedTraversalDocx.length,
            new ByteArrayInputStream(nestedTraversalDocx)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);

        byte[] backslashTraversalDocx = createCustomZipBytes(
            new String[]{"[Content_Types].xml", "word/document.xml", "..\\evil.xml"},
            new byte[][]{
                contentTypesXmlBytes(),
                "<w:document/>".getBytes(StandardCharsets.UTF_8),
                "<evil/>".getBytes(StandardCharsets.UTF_8)
            }
        );
        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "backslash-slip.docx",
            backslashTraversalDocx.length,
            new ByteArrayInputStream(backslashTraversalDocx)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void acceptsDocxWithDoubleDotInsideAssetFilenameAndRejectsXlsxPptxOrGenericRelsZip() throws IOException {
        byte[] chartDoubleDotDocx = createCustomZipBytes(
            new String[]{"[Content_Types].xml", "word/document.xml", "word/media/chart..v2.xml"},
            new byte[][]{
                contentTypesXmlBytes(),
                "<w:document/>".getBytes(StandardCharsets.UTF_8),
                "<chart/>".getBytes(StandardCharsets.UTF_8)
            }
        );
        FileValidationResult chartResult = validator.validateAndPrepareStream(
            "chart-report.docx",
            chartDoubleDotDocx.length,
            new ByteArrayInputStream(chartDoubleDotDocx)
        );
        assertThat(chartResult.fileExtension()).isEqualTo("docx");
        assertThat(chartResult.canonicalMimeType()).isEqualTo(DOCX_MIME_TYPE);

        String xlsxContentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            </Types>
            """;
        byte[] renamedXlsx = createCustomZipBytes(
            new String[]{"[Content_Types].xml", "_rels/.rels", "xl/workbook.xml"},
            new byte[][]{
                xlsxContentTypes.getBytes(StandardCharsets.UTF_8),
                "<Relationships/>".getBytes(StandardCharsets.UTF_8),
                "<workbook/>".getBytes(StandardCharsets.UTF_8)
            }
        );
        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "budget.docx",
            renamedXlsx.length,
            new ByteArrayInputStream(renamedXlsx)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);

        String pptxContentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
            </Types>
            """;
        byte[] renamedPptx = createCustomZipBytes(
            new String[]{"[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml"},
            new byte[][]{
                pptxContentTypes.getBytes(StandardCharsets.UTF_8),
                "<Relationships/>".getBytes(StandardCharsets.UTF_8),
                "<presentation/>".getBytes(StandardCharsets.UTF_8)
            }
        );
        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "deck.docx",
            renamedPptx.length,
            new ByteArrayInputStream(renamedPptx)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);

        byte[] relsOnlyZip = createCustomZipBytes(
            new String[]{"_rels/.rels", "notes.txt"},
            new byte[][]{
                "<Relationships/>".getBytes(StandardCharsets.UTF_8),
                "not a word document".getBytes(StandardCharsets.UTF_8)
            }
        );
        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "generic.docx",
            relsOnlyZip.length,
            new ByteArrayInputStream(relsOnlyZip)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void rejectsCorruptFakeZipHeaderNamedCorruptDocx() {
        byte[] corruptPkHeader = new byte[]{'P', 'K', 0x03, 0x04, (byte) 0xFF, (byte) 0xFE, 0x12, 0x34, 0x56, 0x78};

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "corrupt.docx",
            corruptPkHeader.length,
            new ByteArrayInputStream(corruptPkHeader)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void rejectsBinaryNullBytesAndPdfStreamNamedTxtOrMd() {
        byte[] nullBytePayload = new byte[]{'H', 'e', 'l', 'l', 'o', 0x00, 'W', 'o', 'r', 'l', 'd'};
        byte[] pdfPayload = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "notes.txt",
            nullBytePayload.length,
            new ByteArrayInputStream(nullBytePayload)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "readme.md",
            nullBytePayload.length,
            new ByteArrayInputStream(nullBytePayload)
        )).isInstanceOf(UnsupportedDocumentTypeException.class);

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "notes.txt",
            pdfPayload.length,
            new ByteArrayInputStream(pdfPayload)
        ))
            .isInstanceOf(UnsupportedDocumentTypeException.class)
            .satisfies(ex -> assertThat(((UnsupportedDocumentTypeException) ex).detectedMimeType())
                .isEqualTo("application/pdf"));

        assertThatThrownBy(() -> validator.validateAndPrepareStream(
            "readme.md",
            pdfPayload.length,
            new ByteArrayInputStream(pdfPayload)
        ))
            .isInstanceOf(UnsupportedDocumentTypeException.class)
            .satisfies(ex -> assertThat(((UnsupportedDocumentTypeException) ex).detectedMimeType())
                .isEqualTo("application/pdf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<doc><title>Config</title></doc>",
        "{\"service\":\"documind\",\"threshold\":0.75}",
        "#!/usr/bin/env bash\necho 'DocuMind health check'"
    })
    void allowsValidXmlJsonAndShellTextUploadsAsTxtAndMarkdownWithoutNullBytes(String textPayload) throws IOException {
        byte[] bytes = textPayload.getBytes(StandardCharsets.UTF_8);

        FileValidationResult txtResult = validator.validateAndPrepareStream(
            "snippet.txt",
            bytes.length,
            new ByteArrayInputStream(bytes)
        );
        assertThat(txtResult.canonicalMimeType()).isEqualTo("text/plain");
        assertThat(txtResult.rewindedStream().readAllBytes()).isEqualTo(bytes);

        FileValidationResult mdResult = validator.validateAndPrepareStream(
            "snippet.md",
            bytes.length,
            new ByteArrayInputStream(bytes)
        );
        assertThat(mdResult.canonicalMimeType()).isEqualTo("text/markdown");
        assertThat(mdResult.rewindedStream().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void sanitizesStreamIoExceptionWithoutLeakingFilesystemPaths() {
        InputStream brokenStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("D:\\internal\\tomcat-work\\part-999.tmp (Access is denied)");
            }
        };

        assertThatThrownBy(() -> validator.validateAndPrepareStream("notes.txt", 128L, brokenStream))
            .isInstanceOf(InvalidDocumentRequestException.class)
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain("tomcat-work")
                .doesNotContain("part-999.tmp"));
    }

    private static byte[] createValidDocxBytes(String documentXml) throws IOException {
        String relsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;
        return createCustomZipBytes(
            new String[]{"[Content_Types].xml", "_rels/.rels", "word/document.xml"},
            new byte[][]{
                contentTypesXmlBytes(),
                relsXml.getBytes(StandardCharsets.UTF_8),
                documentXml.getBytes(StandardCharsets.UTF_8)
            }
        );
    }

    private static byte[] createLargeDocxExceeding64KiB() throws IOException {
        Random deterministicRandom = new Random(20261116L);
        StringBuilder largeXml = new StringBuilder(220_000);
        largeXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document><w:body><w:p><w:t>");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 200_000; i++) {
            largeXml.append(alphabet.charAt(deterministicRandom.nextInt(alphabet.length())));
        }
        largeXml.append("</w:t></w:p></w:body></w:document>");

        return createValidDocxBytes(largeXml.toString());
    }

    private static byte[] contentTypesXmlBytes() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] createCustomZipBytes(String[] entryNames, byte[][] entryPayloads) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < entryNames.length; i++) {
                zos.putNextEntry(new ZipEntry(entryNames[i]));
                zos.write(entryPayloads[i]);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
