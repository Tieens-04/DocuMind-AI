package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.util.ZipFileZipEntrySource;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.microsoft.ooxml.OPCPackageWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Extracts {@code .docx} files using Apache POI {@link ZipSecureFile} ZIP-bomb limits, strict container validation,
 * and a heading-aware Tika XHTML handler that preserves Word heading breadcrumbs and nested/pipe-delimited tables.
 *
 * <p><strong>JVM-global state note:</strong> {@link ZipSecureFile} stores {@code minInflateRatio}, {@code maxEntrySize},
 * {@code maxFileCount}, and {@code maxTextSize} in static JVM-wide globals, which affects every Apache POI OOXML
 * consumer running in the same JVM process.
 */
@Component
public class DocxDocumentExtractor {

    private static final int MAX_HEADING_LEVELS = 6;
    private static final int HEADER_PROBE_BYTES = 64;
    private static final long MIN_TIKA_MAX_TEXT_SIZE_BYTES = 10_485_760L;
    private static final String WORD_DOC_MAIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String WORD_DOC_MACRO_CONTENT_TYPE =
        "application/vnd.ms-word.document.macroEnabled.main+xml";
    private static final String WORD_TEMPLATE_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml";
    private static final String OFFICE_DOC_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private static final String STRICT_OFFICE_DOC_REL_TYPE =
        "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument";
    private static final Object ZIP_CONFIG_LOCK = new Object();
    // Eagerly initialize Tika's OOXMLParser once so OOXMLParser.<clinit>()'s ZipSecureFile reset runs before we apply our strict limits.
    private static final OOXMLParser OOXML_PARSER = new OOXMLParser();

    private final IngestionProperties properties;
    private final PlainTextSectionExtractor plainTextSectionExtractor;

    @Autowired
    public DocxDocumentExtractor(
        IngestionProperties properties,
        PlainTextSectionExtractor plainTextSectionExtractor
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.plainTextSectionExtractor = Objects.requireNonNull(
            plainTextSectionExtractor,
            "plainTextSectionExtractor must not be null"
        );
        ensureZipSecureLimits(this.properties.extraction());
    }

    public List<ExtractedSegment> extract(Path docxTempFile, String originalName) {
        Objects.requireNonNull(docxTempFile, "docxTempFile must not be null");
        String displayName = (originalName == null || originalName.isBlank()) ? "document.docx" : originalName;
        verifyNonEmptyFile(docxTempFile, displayName);
        ensureZipSecureLimits(this.properties.extraction());

        HeadingAwareXhtmlContentHandler handler = new HeadingAwareXhtmlContentHandler(
            properties.extraction().maxExtractedChars(),
            displayName
        );
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, displayName);

        OfficeParserConfig officeConfig = new OfficeParserConfig();
        ParseContext parseContext = new ParseContext();
        parseContext.set(OfficeParserConfig.class, officeConfig);

        try {
            byte[] header = readHeaderPrefix(docxTempFile);
            if (startsWithPkZipSignature(header)) {
                try (ZipSecureFile secureZip = new ZipSecureFile(docxTempFile.toFile());
                     ZipFileZipEntrySource entrySource = new ZipFileZipEntrySource(secureZip);
                     OPCPackage opcPackage = OPCPackage.open(entrySource);
                     OPCPackageWrapper wrapper = new OPCPackageWrapper(opcPackage);
                     TikaInputStream tikaStream = TikaInputStream.get(docxTempFile, metadata)) {
                    if (!isWordProcessingPackage(opcPackage)) {
                        throw new DocumentExtractionException(
                            DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                            "ZIP container is not a valid WordprocessingML .docx document: " + displayName
                        );
                    }
                    tikaStream.setOpenContainer(wrapper);
                    OOXML_PARSER.parse(tikaStream, handler, metadata, parseContext);
                }
            } else if (startsWithOle2Signature(header)) {
                inspectOle2Container(docxTempFile, displayName);
            } else if (isRecognizableNonDocxFormat(header)) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                    "File content does not match .docx OOXML container format: " + displayName
                );
            } else {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                    "Corrupt or unrecognized .docx container header: " + displayName
                );
            }
        } catch (Exception ex) {
            throw translateExtractionFailure(ex, displayName);
        }

        List<ExtractedSegment> segments = handler.segments();
        if (segments.isEmpty()) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                "DOCX document contains no extractable body text: " + displayName
            );
        }
        if (!handler.sawHeadingTag()) {
            StringBuilder combined = new StringBuilder();
            for (ExtractedSegment segment : segments) {
                if (!combined.isEmpty()) {
                    combined.append("\n\n");
                }
                combined.append(segment.content());
            }
            return plainTextSectionExtractor.extractFromText(combined.toString(), displayName);
        }
        return segments;
    }

    /**
     * Applies JVM-wide {@link ZipSecureFile} thresholds once (or when configuration changes) under a lock.
     */
    private static void ensureZipSecureLimits(IngestionProperties.ExtractionProperties cfg) {
        if (limitsMatch(cfg)) {
            return;
        }
        synchronized (ZIP_CONFIG_LOCK) {
            if (!limitsMatch(cfg)) {
                ZipSecureFile.setMinInflateRatio(cfg.zipMinInflateRatio());
                ZipSecureFile.setMaxEntrySize(cfg.zipMaxEntrySizeBytes());
                ZipSecureFile.setMaxFileCount(cfg.zipMaxFileCount());
                ZipSecureFile.setMaxTextSize(MIN_TIKA_MAX_TEXT_SIZE_BYTES);
            }
        }
    }

    private static boolean limitsMatch(IngestionProperties.ExtractionProperties cfg) {
        return Double.compare(ZipSecureFile.getMinInflateRatio(), cfg.zipMinInflateRatio()) == 0
            && ZipSecureFile.getMaxEntrySize() == cfg.zipMaxEntrySizeBytes()
            && ZipSecureFile.getMaxFileCount() == cfg.zipMaxFileCount()
            && ZipSecureFile.getMaxTextSize() == MIN_TIKA_MAX_TEXT_SIZE_BYTES;
    }

    private static void verifyNonEmptyFile(Path docxTempFile, String displayName) {
        try {
            if (Files.size(docxTempFile) == 0L) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EMPTY_DOCUMENT,
                    "DOCX file is empty (0 bytes): " + displayName
                );
            }
        } catch (IOException ex) {
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Unable to inspect spooled DOCX file: " + displayName,
                ex
            );
        }
    }

    private static RuntimeException translateExtractionFailure(Throwable ex, String displayName) {
        if (ex instanceof DocumentExtractionException dee) {
            return dee;
        }
        if (hasCause(ex, LimitExceededSaxException.class) || isZipBombOrLimitMessage(ex)) {
            return new DocumentExtractionException(
                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                "DOCX extraction exceeded safety limits (ZIP-bomb or character cap): " + displayName,
                ex
            );
        }
        if (hasCause(ex, EncryptedDocumentException.class)
            || hasCause(ex, org.apache.poi.EncryptedDocumentException.class)) {
            return new DocumentExtractionException(
                DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                "DOCX document is password-protected or encrypted: " + displayName,
                ex
            );
        }
        if (ex instanceof IOException
            || ex instanceof TikaException
            || ex instanceof SAXException
            || ex instanceof OpenXML4JException) {
            return new DocumentExtractionException(
                DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
                "Failed to parse DOCX document: " + displayName,
                ex
            );
        }
        return new DocumentExtractionException(
            DocumentExtractionErrorCode.CORRUPT_DOCUMENT,
            "Unexpected error extracting DOCX document: " + displayName,
            ex
        );
    }

    private static byte[] readHeaderPrefix(Path file) throws IOException {
        byte[] buffer = new byte[HEADER_PROBE_BYTES];
        int offset = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
        }
        return offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
    }

    private static boolean startsWithPkZipSignature(byte[] magic) {
        return magic.length >= 4
            && magic[0] == 0x50
            && magic[1] == 0x4B
            && ((magic[2] == 0x03 && magic[3] == 0x04)
                || (magic[2] == 0x05 && magic[3] == 0x06)
                || (magic[2] == 0x07 && magic[3] == 0x08));
    }

    private static boolean startsWithOle2Signature(byte[] magic) {
        return magic.length >= 8
            && magic[0] == (byte) 0xD0
            && magic[1] == (byte) 0xCF
            && magic[2] == (byte) 0x11
            && magic[3] == (byte) 0xE0
            && magic[4] == (byte) 0xA1
            && magic[5] == (byte) 0xB1
            && magic[6] == (byte) 0x1A
            && magic[7] == (byte) 0xE1;
    }

    private static boolean isRecognizableNonDocxFormat(byte[] header) {
        if (header.length >= 5
            && header[0] == '%'
            && header[1] == 'P'
            && header[2] == 'D'
            && header[3] == 'F'
            && header[4] == '-') {
            return true;
        }
        String latin1Prefix = new String(header, StandardCharsets.ISO_8859_1);
        if (latin1Prefix.startsWith("{\\rtf")) {
            return true;
        }
        String utf8Prefix = new String(header, StandardCharsets.UTF_8);
        if (utf8Prefix.startsWith("\uFEFF")) {
            utf8Prefix = utf8Prefix.substring(1);
        }
        String trimmedLower = utf8Prefix.stripLeading().toLowerCase(Locale.ROOT);
        return trimmedLower.startsWith("<html") || trimmedLower.startsWith("<!doctype html");
    }

    private static void inspectOle2Container(Path docxTempFile, String displayName) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(docxTempFile.toFile(), true)) {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntryCaseInsensitive("EncryptionInfo")
                || root.hasEntryCaseInsensitive("EncryptedPackage")
                || root.hasEntryCaseInsensitive("EncryptedSummary")) {
                throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.ENCRYPTED_DOCUMENT,
                    "DOCX document is password-protected or encrypted: " + displayName
                );
            }
            throw new DocumentExtractionException(
                DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                "Legacy OLE2 container is not a supported OOXML .docx document: " + displayName
            );
        }
    }

    private static boolean isWordProcessingPackage(OPCPackage opcPackage) {
        if (!opcPackage.getPartsByContentType(WORD_DOC_MAIN_CONTENT_TYPE).isEmpty()
            || !opcPackage.getPartsByContentType(WORD_DOC_MACRO_CONTENT_TYPE).isEmpty()
            || !opcPackage.getPartsByContentType(WORD_TEMPLATE_CONTENT_TYPE).isEmpty()) {
            return true;
        }
        return hasWordOfficeDocumentRel(opcPackage, OFFICE_DOC_REL_TYPE)
            || hasWordOfficeDocumentRel(opcPackage, STRICT_OFFICE_DOC_REL_TYPE);
    }

    private static boolean hasWordOfficeDocumentRel(OPCPackage opcPackage, String relType) {
        for (PackageRelationship rel : opcPackage.getRelationshipsByType(relType)) {
            URI target = rel.getTargetURI();
            if (target == null || target.getPath() == null) {
                continue;
            }
            String path = target.getPath().toLowerCase(Locale.ROOT);
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.startsWith("word/")) {
                return true;
            }
        }
        return false;
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

    private static boolean isZipBombOrLimitMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("zip bomb")
                    || lower.contains("inflate ratio")
                    || lower.contains("max_entry_size")
                    || lower.contains("max_file_count")
                    || lower.contains("max_text_size")
                    || lower.contains("min_inflate_ratio")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static final class LimitExceededSaxException extends SAXException {
        private LimitExceededSaxException(String message) {
            super(message);
        }
    }

    private static final class HeadingAwareXhtmlContentHandler extends DefaultHandler {

        private final int maxExtractedChars;
        private final String displayName;
        private final List<ExtractedSegment> segments = new ArrayList<>();
        private final String[] headings = new String[MAX_HEADING_LEVELS];
        private final boolean[] dirtyHeadings = new boolean[MAX_HEADING_LEVELS];
        private final StringBuilder bodyBuffer = new StringBuilder();
        private final StringBuilder headingBuffer = new StringBuilder();
        private final Deque<List<String>> rowStack = new ArrayDeque<>();
        private final Deque<StringBuilder> cellStack = new ArrayDeque<>();

        private boolean inBody;
        private int activeHeadingLevel;
        private boolean sawHeadingTag;
        private int totalChars;

        private HeadingAwareXhtmlContentHandler(int maxExtractedChars, String displayName) {
            this.maxExtractedChars = maxExtractedChars;
            this.displayName = displayName;
        }

        private boolean sawHeadingTag() {
            return sawHeadingTag;
        }

        private List<ExtractedSegment> segments() {
            flushSegment();
            flushOrphanedHeadingIfNeeded(0);
            return List.copyOf(segments);
        }

        private boolean inTableCell() {
            return !cellStack.isEmpty();
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String tag = resolveTag(localName, qName);
            if ("body".equals(tag)) {
                inBody = true;
                return;
            }
            if (!inBody) {
                return;
            }
            if (!inTableCell()) {
                int headingLevel = parseHeadingLevel(tag);
                if (headingLevel > 0) {
                    sawHeadingTag = true;
                    flushSegment();
                    activeHeadingLevel = headingLevel;
                    headingBuffer.setLength(0);
                    return;
                }
            }
            if ("tr".equals(tag)) {
                rowStack.push(new ArrayList<>());
            } else if ("td".equals(tag) || "th".equals(tag)) {
                cellStack.push(new StringBuilder());
            } else if ("br".equals(tag)) {
                appendNewline(inTableCell() ? cellStack.peek() : bodyBuffer);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            appendText(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            appendText(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String tag = resolveTag(localName, qName);
            if ("body".equals(tag)) {
                flushSegment();
                flushOrphanedHeadingIfNeeded(0);
                inBody = false;
                return;
            }
            if (!inBody) {
                return;
            }
            if (!inTableCell()) {
                int headingLevel = parseHeadingLevel(tag);
                if (headingLevel > 0 && activeHeadingLevel == headingLevel) {
                    String cleanHeading = IngestionTextNormalizer.normalizeText(headingBuffer.toString())
                        .replace('\n', ' ');
                    headingBuffer.setLength(0);
                    activeHeadingLevel = 0;
                    if (!cleanHeading.isEmpty()) {
                        flushOrphanedHeadingIfNeeded(headingLevel - 1);
                        headings[headingLevel - 1] = cleanHeading;
                        Arrays.fill(headings, headingLevel, headings.length, null);
                        dirtyHeadings[headingLevel - 1] = true;
                    }
                    return;
                }
            }
            if ("td".equals(tag) || "th".equals(tag)) {
                if (!cellStack.isEmpty()) {
                    StringBuilder finishedCell = cellStack.pop();
                    String cell = IngestionTextNormalizer.normalizeText(finishedCell.toString())
                        .replaceAll("\\s+", " ");
                    if (!rowStack.isEmpty()) {
                        rowStack.peek().add(cell);
                    }
                }
                return;
            }
            if ("tr".equals(tag)) {
                if (!rowStack.isEmpty()) {
                    List<String> rowCells = rowStack.pop();
                    if (rowCells.stream().anyMatch(cell -> !cell.isEmpty())) {
                        if (inTableCell()) {
                            List<String> nonEmpty = rowCells.stream().filter(cell -> !cell.isEmpty()).toList();
                            String nestedRow = String.join(" - ", nonEmpty);
                            StringBuilder parentCell = cellStack.peek();
                            if (!parentCell.isEmpty()
                                && !Character.isWhitespace(parentCell.charAt(parentCell.length() - 1))) {
                                parentCell.append(' ');
                            }
                            parentCell.append(nestedRow);
                        } else {
                            if (!bodyBuffer.isEmpty() && bodyBuffer.charAt(bodyBuffer.length() - 1) != '\n') {
                                bodyBuffer.append('\n');
                            }
                            bodyBuffer.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
                        }
                    }
                }
                return;
            }
            if ("p".equals(tag) || "li".equals(tag) || (inTableCell() && parseHeadingLevel(tag) > 0)) {
                if (inTableCell()) {
                    StringBuilder currentCell = cellStack.peek();
                    if (!currentCell.isEmpty()
                        && !Character.isWhitespace(currentCell.charAt(currentCell.length() - 1))) {
                        currentCell.append(' ');
                    }
                } else {
                    appendNewline(bodyBuffer);
                    if ("p".equals(tag)) {
                        appendNewline(bodyBuffer);
                    }
                }
            }
        }

        private void appendText(char[] ch, int start, int length) throws SAXException {
            if (!inBody || length <= 0) {
                return;
            }
            totalChars += length;
            if (totalChars > maxExtractedChars) {
                throw new LimitExceededSaxException(
                    "Extracted DOCX character count exceeded limit of " + maxExtractedChars + " for " + displayName
                );
            }
            if (activeHeadingLevel > 0) {
                headingBuffer.append(ch, start, length);
            } else if (inTableCell()) {
                cellStack.peek().append(ch, start, length);
            } else {
                bodyBuffer.append(ch, start, length);
            }
        }

        private void flushSegment() {
            if (bodyBuffer.isEmpty()) {
                return;
            }
            String content = IngestionTextNormalizer.normalizeText(bodyBuffer.toString());
            bodyBuffer.setLength(0);
            if (!content.isEmpty()) {
                segments.add(new ExtractedSegment(content, null, IngestionTextNormalizer.formatBreadcrumb(headings)));
                Arrays.fill(dirtyHeadings, false);
            }
        }

        private void flushOrphanedHeadingIfNeeded(int targetLevel) {
            int deepestDirty = -1;
            for (int k = targetLevel; k < dirtyHeadings.length; k++) {
                if (dirtyHeadings[k] && headings[k] != null && !headings[k].isBlank()) {
                    deepestDirty = k;
                }
            }
            if (deepestDirty >= 0) {
                String parentBreadcrumb = IngestionTextNormalizer.formatBreadcrumb(Arrays.copyOf(headings, deepestDirty));
                segments.add(new ExtractedSegment(headings[deepestDirty], null, parentBreadcrumb));
                Arrays.fill(dirtyHeadings, false);
            }
        }

        private static void appendNewline(StringBuilder target) {
            if (!target.isEmpty()) {
                target.append('\n');
            }
        }

        private static int parseHeadingLevel(String tag) {
            if (tag.length() == 2 && tag.charAt(0) == 'h') {
                char digit = tag.charAt(1);
                if (digit >= '1' && digit <= '6') {
                    return digit - '0';
                }
            }
            return 0;
        }

        private static String resolveTag(String localName, String qName) {
            String raw = (localName != null && !localName.isEmpty()) ? localName : qName;
            return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        }
    }
}
