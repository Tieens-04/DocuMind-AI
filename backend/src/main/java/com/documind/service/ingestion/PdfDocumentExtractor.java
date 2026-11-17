package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Extracts text from spooled PDF files using a 3-tier page-preserving pipeline while closing all PDFBox/file handles
 * cleanly for Windows temp-file deletion safety.
 */
@Component
public class PdfDocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentExtractor.class);
    private static final Pattern MULTI_HORIZONTAL_SPACE = Pattern.compile("[ \\t]{2,}");

    private final IngestionProperties properties;
    private final Function<Path, Map<Integer, String>> tier1Extractor;

    @Autowired
    public PdfDocumentExtractor(IngestionProperties properties) {
        this(properties, PdfDocumentExtractor::extractViaPagePdfReader);
    }

    PdfDocumentExtractor(IngestionProperties properties, Function<Path, Map<Integer, String>> tier1Extractor) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.tier1Extractor = tier1Extractor != null ? tier1Extractor : PdfDocumentExtractor::extractViaPagePdfReader;
    }

    public List<ExtractedSegment> extract(Path pdfTempFile, String originalName) {
        Objects.requireNonNull(pdfTempFile, "pdfTempFile must not be null");
        String displayName = safeDisplayName(originalName);
        verifyNonEmptyFile(pdfTempFile, displayName);

        int totalPages;
        Map<Integer, String> resolvedPages = new LinkedHashMap<>();

        try (PDDocument pdDoc = Loader.loadPDF(pdfTempFile.toFile())) {
            totalPages = pdDoc.getNumberOfPages();
            if (totalPages == 0) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                    "PDF document contains 0 pages: " + displayName
                );
            }

            collectTier1Pages(pdfTempFile, displayName, totalPages, resolvedPages);
            rescueMissingPagesWithStripper(pdDoc, totalPages, displayName, resolvedPages);
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                "PDF document is password-protected or encrypted: " + displayName,
                ex
            );
        } catch (IOException ex) {
            if (hasCause(ex, InvalidPasswordException.class)) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                    "PDF document is password-protected or encrypted: " + displayName,
                    ex
                );
            }
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed to parse PDF document structure: " + displayName,
                ex
            );
        }

        if (allPagesBlank(resolvedPages)) {
            Map<Integer, String> tier3Pages = extractViaTikaPageSax(pdfTempFile, displayName);
            for (Map.Entry<Integer, String> entry : tier3Pages.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    resolvedPages.put(entry.getKey(), collapseLayoutPadding(entry.getValue()));
                }
            }
        }

        return buildSegments(resolvedPages, totalPages, displayName);
    }

    private void collectTier1Pages(
        Path pdfTempFile,
        String displayName,
        int totalPages,
        Map<Integer, String> resolvedPages
    ) {
        try {
            Map<Integer, String> tier1Pages = tier1Extractor.apply(pdfTempFile);
            if (tier1Pages == null) {
                return;
            }
            for (Map.Entry<Integer, String> entry : tier1Pages.entrySet()) {
                Integer pageNum = entry.getKey();
                String text = entry.getValue();
                if (pageNum != null && pageNum >= 1 && pageNum <= totalPages && text != null && !text.isBlank()) {
                    resolvedPages.put(pageNum, text);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Tier-1 PagePdfDocumentReader failed for '{}'; falling back to PDFTextStripper: {}",
                displayName, ex.getMessage());
        }
    }

    private static void rescueMissingPagesWithStripper(
        PDDocument pdDoc,
        int totalPages,
        String displayName,
        Map<Integer, String> resolvedPages
    ) {
        boolean needsRescue = false;
        for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
            String existing = resolvedPages.get(pageNum);
            if (existing == null || existing.isBlank()) {
                needsRescue = true;
                break;
            }
        }
        if (!needsRescue) {
            return;
        }

        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                String existing = resolvedPages.get(pageNum);
                if (existing != null && !existing.isBlank()) {
                    continue;
                }
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String rescued = stripper.getText(pdDoc);
                if (rescued != null && !rescued.isBlank()) {
                    resolvedPages.put(pageNum, rescued);
                }
            }
        } catch (IOException ex) {
            log.warn("Tier-2 PDFTextStripper rescue encountered an I/O error for '{}': {}",
                displayName, ex.getMessage());
        }
    }

    Map<Integer, String> extractViaTikaPageSax(Path pdfTempFile, String originalName) {
        String displayName = safeDisplayName(originalName);
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setSortByPosition(true);
        pdfConfig.setExtractAcroFormContent(true);
        pdfConfig.setExtractAnnotationText(true);

        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);

        PageDivSaxHandler handler = new PageDivSaxHandler(properties.extraction().maxExtractedChars(), displayName);
        PDFParser parser = new PDFParser();
        Metadata metadata = new Metadata();

        try (InputStream in = Files.newInputStream(pdfTempFile)) {
            parser.parse(in, handler, metadata, context);
            return handler.pages();
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (EncryptedDocumentException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                "PDF document is password-protected or encrypted: " + displayName,
                ex
            );
        } catch (LimitExceededSaxException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                ex.getMessage(),
                ex
            );
        } catch (IOException | TikaException | SAXException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed to extract PDF pages via Tika fallback: " + displayName,
                ex
            );
        }
    }

    private List<ExtractedSegment> buildSegments(
        Map<Integer, String> resolvedPages,
        int totalPages,
        String displayName
    ) {
        int maxChars = properties.extraction().maxExtractedChars();
        int cumulativeChars = 0;
        List<ExtractedSegment> segments = new ArrayList<>();

        for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
            String rawPage = resolvedPages.get(pageNum);
            if (rawPage == null || rawPage.isBlank()) {
                continue;
            }
            String normalized = IngestionTextNormalizer.normalizeText(collapseLayoutPadding(rawPage));
            if (normalized.isEmpty()) {
                continue;
            }
            cumulativeChars += normalized.length();
            if (cumulativeChars > maxChars) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                    "Extracted PDF character count exceeded limit of " + maxChars + " for " + displayName
                );
            }
            segments.add(new ExtractedSegment(normalized, pageNum, null));
        }

        if (segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT,
                "PDF contains no extractable text layer (likely scanned image PDF): " + displayName
            );
        }
        return List.copyOf(segments);
    }

    private static Map<Integer, String> extractViaPagePdfReader(Path pdfTempFile) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
            .withPagesPerDocument(1)
            .withPageTopMargin(0)
            .withPageBottomMargin(0)
            .build();

        Map<Integer, String> pages = new LinkedHashMap<>();
        try (TrackingFileSystemResource resource = new TrackingFileSystemResource(pdfTempFile);
             CloseablePagePdfDocumentReader reader = new CloseablePagePdfDocumentReader(resource, config)) {
            List<Document> docs = reader.get();
            for (Document doc : docs) {
                Object rawPageNumber = doc.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
                if (rawPageNumber instanceof Number num) {
                    int pageNum = num.intValue();
                    String text = doc.getText();
                    if (pageNum >= 1 && text != null && !text.isBlank()) {
                        pages.merge(pageNum, text, (a, b) -> a + "\n\n" + b);
                    }
                }
            }
        }
        return pages;
    }

    private static boolean allPagesBlank(Map<Integer, String> pages) {
        return pages.values().stream().noneMatch(text -> text != null && !text.isBlank());
    }

    private static void verifyNonEmptyFile(Path pdfTempFile, String displayName) {
        try {
            if (Files.size(pdfTempFile) == 0L) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                    "PDF file is empty (0 bytes): " + displayName
                );
            }
        } catch (IOException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Unable to inspect spooled PDF file: " + displayName,
                ex
            );
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> target) {
        Throwable cursor = error;
        while (cursor != null) {
            if (target.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String collapseLayoutPadding(String rawPage) {
        return MULTI_HORIZONTAL_SPACE.matcher(rawPage).replaceAll(" ");
    }

    private static String safeDisplayName(String originalName) {
        return (originalName == null || originalName.isBlank()) ? "document.pdf" : originalName;
    }

    /**
     * Wraps {@link PagePdfDocumentReader} as {@link AutoCloseable} so its underlying {@link PDDocument} is always closed.
     */
    private static final class CloseablePagePdfDocumentReader extends PagePdfDocumentReader implements AutoCloseable {

        private CloseablePagePdfDocumentReader(Resource resource, PdfDocumentReaderConfig config) {
            super(resource, config);
        }

        @Override
        public void close() {
            if (this.document != null) {
                try {
                    this.document.close();
                } catch (IOException ex) {
                    log.debug("Failed to close PDFBox PDDocument quietly: {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * Tracks every {@link InputStream} opened by Spring AI's reader and closes them deterministically on exit.
     */
    private static final class TrackingFileSystemResource extends FileSystemResource implements AutoCloseable {

        private final List<InputStream> openedStreams = new ArrayList<>();

        private TrackingFileSystemResource(Path filePath) {
            super(filePath);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream stream = super.getInputStream();
            openedStreams.add(stream);
            return stream;
        }

        @Override
        public void close() {
            for (InputStream stream : openedStreams) {
                try {
                    stream.close();
                } catch (IOException ex) {
                    log.debug("Failed to close tracked PDF InputStream: {}", ex.getMessage());
                }
            }
            openedStreams.clear();
        }
    }

    private static final class LimitExceededSaxException extends SAXException {
        private LimitExceededSaxException(String message) {
            super(message);
        }
    }

    private static final class PageDivSaxHandler extends DefaultHandler {

        private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6");

        private final int maxExtractedChars;
        private final String displayName;
        private final Map<Integer, String> pages = new LinkedHashMap<>();
        private final StringBuilder currentPageBuffer = new StringBuilder();

        private int currentPageNumber;
        private int pageDivDepth;
        private int totalExtractedChars;

        private PageDivSaxHandler(int maxExtractedChars, String displayName) {
            this.maxExtractedChars = maxExtractedChars;
            this.displayName = displayName;
        }

        private Map<Integer, String> pages() {
            return pages;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String tag = resolveTag(localName, qName);
            if (pageDivDepth > 0) {
                if ("div".equals(tag)) {
                    pageDivDepth++;
                }
                return;
            }
            if ("div".equals(tag) && hasCssClass(attributes, "page")) {
                currentPageNumber++;
                pageDivDepth = 1;
                currentPageBuffer.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            appendChars(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            appendChars(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (pageDivDepth <= 0) {
                return;
            }
            String tag = resolveTag(localName, qName);
            if ("div".equals(tag)) {
                pageDivDepth--;
                if (pageDivDepth == 0) {
                    String text = currentPageBuffer.toString();
                    currentPageBuffer.setLength(0);
                    if (!text.isBlank()) {
                        pages.put(currentPageNumber, text);
                    }
                    return;
                }
            }
            if (BLOCK_TAGS.contains(tag) && !currentPageBuffer.isEmpty()
                && currentPageBuffer.charAt(currentPageBuffer.length() - 1) != '\n') {
                currentPageBuffer.append('\n');
            }
        }

        private void appendChars(char[] ch, int start, int length) throws SAXException {
            if (pageDivDepth <= 0 || length <= 0) {
                return;
            }
            totalExtractedChars += length;
            if (totalExtractedChars > maxExtractedChars) {
                throw new LimitExceededSaxException(
                    "Extracted PDF character count exceeded limit of " + maxExtractedChars + " for " + displayName
                );
            }
            currentPageBuffer.append(ch, start, length);
        }

        private static String resolveTag(String localName, String qName) {
            String raw = (localName != null && !localName.isEmpty()) ? localName : qName;
            return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        }

        private static boolean hasCssClass(Attributes attributes, String targetClass) {
            if (attributes == null) {
                return false;
            }
            String classAttr = attributes.getValue("class");
            if (classAttr == null || classAttr.isBlank()) {
                return false;
            }
            for (String token : classAttr.trim().split("\\s+")) {
                if (targetClass.equalsIgnoreCase(token)) {
                    return true;
                }
            }
            return false;
        }
    }
}
