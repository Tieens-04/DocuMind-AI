package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
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
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Extracts text from spooled PDF files using a bounded-concurrency, sequential 3-tier page-preserving pipeline while
 * closing all PDFBox/file handles cleanly for Windows temp-file deletion safety.
 */
@Component
public class PdfDocumentExtractor {

    public static final int DEFAULT_MAX_CONCURRENT_EXTRACTIONS = 8;

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentExtractor.class);
    private static final Pattern MULTI_HORIZONTAL_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final long RAW_PAGE_CHAR_MULTIPLIER = 4L;

    private final IngestionProperties properties;
    private final Function<Path, Map<Integer, String>> tier1Extractor;
    private final Semaphore concurrencyLimiter;
    private final Supplier<PageStripper> pageStripperFactory;

    @Autowired
    public PdfDocumentExtractor(IngestionProperties properties) {
        this(
            properties,
            PdfDocumentExtractor::extractViaPagePdfReader,
            DEFAULT_MAX_CONCURRENT_EXTRACTIONS,
            defaultPageStripperFactory()
        );
    }

    PdfDocumentExtractor(IngestionProperties properties, Function<Path, Map<Integer, String>> tier1Extractor) {
        this(
            properties,
            tier1Extractor,
            DEFAULT_MAX_CONCURRENT_EXTRACTIONS,
            defaultPageStripperFactory()
        );
    }

    PdfDocumentExtractor(
        IngestionProperties properties,
        Function<Path, Map<Integer, String>> tier1Extractor,
        int maxConcurrentExtractions
    ) {
        this(properties, tier1Extractor, maxConcurrentExtractions, defaultPageStripperFactory());
    }

    PdfDocumentExtractor(
        IngestionProperties properties,
        Function<Path, Map<Integer, String>> tier1Extractor,
        int maxConcurrentExtractions,
        Supplier<PageStripper> pageStripperFactory
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        if (maxConcurrentExtractions < 1) {
            throw new IllegalArgumentException("maxConcurrentExtractions must be >= 1");
        }
        this.tier1Extractor = tier1Extractor != null ? tier1Extractor : PdfDocumentExtractor::extractViaPagePdfReader;
        this.concurrencyLimiter = new Semaphore(maxConcurrentExtractions);
        this.pageStripperFactory = Objects.requireNonNull(pageStripperFactory, "pageStripperFactory must not be null");
    }

    public List<ExtractedSegment> extract(Path pdfTempFile, String originalName) {
        Objects.requireNonNull(pdfTempFile, "pdfTempFile must not be null");
        String displayName = safeDisplayName(originalName);
        verifyNonEmptyFile(pdfTempFile, displayName);

        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Interrupted while waiting to extract PDF: " + displayName,
                ex
            );
        }

        try {
            return extractInternal(pdfTempFile, displayName);
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Unexpected error parsing PDF document: " + displayName,
                ex
            );
        } finally {
            concurrencyLimiter.release();
        }
    }

    private List<ExtractedSegment> extractInternal(Path pdfTempFile, String displayName) {
        int totalPages = inspectTotalPagesAndEncryption(pdfTempFile, displayName);
        int maxChars = properties.extraction().maxExtractedChars();
        Map<Integer, String> resolvedPages = new LinkedHashMap<>();

        int cumulativeChars = collectTier1Pages(pdfTempFile, displayName, totalPages, maxChars, resolvedPages);

        if (hasMissingPages(resolvedPages, totalPages)) {
            cumulativeChars = rescueMissingPages(
                pdfTempFile,
                totalPages,
                displayName,
                maxChars,
                resolvedPages,
                cumulativeChars
            );
        }

        if (resolvedPages.isEmpty()) {
            Map<Integer, String> tier3Pages = extractViaTikaPageSax(pdfTempFile, displayName);
            for (Map.Entry<Integer, String> entry : tier3Pages.entrySet()) {
                Integer pageNum = entry.getKey();
                String raw = entry.getValue();
                if (pageNum != null && pageNum >= 1 && pageNum <= totalPages && raw != null && !raw.isBlank()) {
                    checkRawPageLength(raw, maxChars, displayName);
                    String normalized = normalizePageText(raw);
                    if (!normalized.isEmpty()) {
                        cumulativeChars = addAndCheckCharBudget(
                            cumulativeChars,
                            normalized.length(),
                            maxChars,
                            displayName
                        );
                        resolvedPages.put(pageNum, normalized);
                    }
                }
            }
        }

        return buildSegments(resolvedPages, totalPages, displayName);
    }

    private static int inspectTotalPagesAndEncryption(Path pdfTempFile, String displayName) {
        try (PDDocument preflightDoc = Loader.loadPDF(pdfTempFile.toFile())) {
            int totalPages = preflightDoc.getNumberOfPages();
            if (totalPages == 0) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                    "PDF document contains 0 pages: " + displayName
                );
            }
            return totalPages;
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                "PDF document is password-protected or encrypted: " + displayName,
                ex
            );
        } catch (IOException | RuntimeException ex) {
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
    }

    private int collectTier1Pages(
        Path pdfTempFile,
        String displayName,
        int totalPages,
        int maxChars,
        Map<Integer, String> resolvedPages
    ) {
        int cumulativeChars = 0;
        try {
            Map<Integer, String> tier1Pages = tier1Extractor.apply(pdfTempFile);
            if (tier1Pages == null || tier1Pages.isEmpty()) {
                return 0;
            }
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                String raw = tier1Pages.get(pageNum);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                checkRawPageLength(raw, maxChars, displayName);
                String normalized = normalizePageText(raw);
                if (!normalized.isEmpty()) {
                    cumulativeChars = addAndCheckCharBudget(cumulativeChars, normalized.length(), maxChars, displayName);
                    resolvedPages.put(pageNum, normalized);
                }
            }
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Tier-1 PagePdfDocumentReader failed for '{}'; falling back to PDFTextStripper: {}",
                displayName, ex.getMessage());
        }
        return cumulativeChars;
    }

    private int rescueMissingPages(
        Path pdfTempFile,
        int totalPages,
        String displayName,
        int maxChars,
        Map<Integer, String> resolvedPages,
        int initialCumulativeChars
    ) {
        try (PDDocument rescueDoc = Loader.loadPDF(pdfTempFile.toFile())) {
            return rescueMissingPagesWithStripper(
                rescueDoc,
                totalPages,
                displayName,
                maxChars,
                resolvedPages,
                initialCumulativeChars
            );
        } catch (DocumentExtractionException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                "PDF document is password-protected or encrypted: " + displayName,
                ex
            );
        } catch (IOException | RuntimeException ex) {
            if (hasCause(ex, InvalidPasswordException.class)) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                    "PDF document is password-protected or encrypted: " + displayName,
                    ex
                );
            }
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed to load PDF document for Tier-2 text rescue: " + displayName,
                ex
            );
        }
    }

    private int rescueMissingPagesWithStripper(
        PDDocument pdDoc,
        int totalPages,
        String displayName,
        int maxChars,
        Map<Integer, String> resolvedPages,
        int initialCumulativeChars
    ) {
        int cumulativeChars = initialCumulativeChars;
        PageStripper stripper = pageStripperFactory.get();
        for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
            String existing = resolvedPages.get(pageNum);
            if (existing != null && !existing.isBlank()) {
                continue;
            }
            String rescued;
            try {
                rescued = stripper.extractPage(pdDoc, pageNum);
            } catch (DocumentExtractionException ex) {
                throw ex;
            } catch (IOException | RuntimeException ex) {
                log.warn("Tier-2 PDFTextStripper rescue failed on page {} of '{}': {}",
                    pageNum, displayName, ex.getMessage());
                stripper = pageStripperFactory.get();
                continue;
            }
            if (rescued == null || rescued.isBlank()) {
                continue;
            }
            checkRawPageLength(rescued, maxChars, displayName);
            String normalized = normalizePageText(rescued);
            if (!normalized.isEmpty()) {
                cumulativeChars = addAndCheckCharBudget(cumulativeChars, normalized.length(), maxChars, displayName);
                resolvedPages.put(pageNum, normalized);
            }
        }
        return cumulativeChars;
    }

    Map<Integer, String> extractViaTikaPageSax(Path pdfTempFile, String originalName) {
        String displayName = safeDisplayName(originalName);
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setSortByPosition(true);
        pdfConfig.setExtractAcroFormContent(true);
        pdfConfig.setExtractAnnotationText(true);
        pdfConfig.setCatchIntermediateIOExceptions(false);

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
        } catch (IOException | TikaException | SAXException ex) {
            if (hasCause(ex, LimitExceededSaxException.class) || hasCause(ex, WriteLimitReachedException.class)) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                    "Extracted PDF character count exceeded limit of " + properties.extraction().maxExtractedChars()
                        + " for " + displayName,
                    ex
                );
            }
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed to extract PDF pages via Tika fallback: " + displayName,
                ex
            );
        }
    }

    private static List<ExtractedSegment> buildSegments(
        Map<Integer, String> resolvedPages,
        int totalPages,
        String displayName
    ) {
        List<ExtractedSegment> segments = new ArrayList<>();

        for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
            String pageText = resolvedPages.get(pageNum);
            if (pageText == null || pageText.isBlank()) {
                continue;
            }
            segments.add(new ExtractedSegment(pageText, pageNum, null));
        }

        if (segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.NO_EXTRACTABLE_TEXT,
                "PDF contains no extractable text layer (likely scanned image PDF): " + displayName
            );
        }
        return List.copyOf(segments);
    }

    private static boolean hasMissingPages(Map<Integer, String> resolvedPages, int totalPages) {
        for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
            String existing = resolvedPages.get(pageNum);
            if (existing == null || existing.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void checkRawPageLength(String rawPage, int maxChars, String displayName) {
        if (rawPage.length() > (long) maxChars * RAW_PAGE_CHAR_MULTIPLIER) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                "Extracted PDF character count exceeded limit of " + maxChars + " for " + displayName
            );
        }
    }

    private static int addAndCheckCharBudget(int currentTotal, int addition, int maxChars, String displayName) {
        long nextTotal = (long) currentTotal + addition;
        if (nextTotal > maxChars) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                "Extracted PDF character count exceeded limit of " + maxChars + " for " + displayName
            );
        }
        return (int) nextTotal;
    }

    private static String normalizePageText(String rawPage) {
        return IngestionTextNormalizer.normalizeText(collapseLayoutPadding(rawPage));
    }

    private static Supplier<PageStripper> defaultPageStripperFactory() {
        return () -> {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return (doc, pageNum) -> {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                return stripper.getText(doc);
            };
        };
    }

    @FunctionalInterface
    interface PageStripper {
        String extractPage(PDDocument document, int pageNumber) throws IOException;
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
