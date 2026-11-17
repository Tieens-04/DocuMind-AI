package com.documind.service.ingestion;

import com.documind.exception.DocumentExtractionErrorCode;
import com.documind.exception.DocumentExtractionException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipFileZipEntrySource;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.ooxml.OPCPackageWrapper;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Extracts {@code .docx} files using Apache POI {@link ZipSecureFile} ZIP-bomb limits and a heading-aware Tika XHTML
 * handler that preserves Word heading breadcrumbs and pipe-delimited tables.
 */
@Component
public class DocxDocumentExtractor {

    private static final int MAX_HEADING_LEVELS = 6;
    private static final long MIN_TIKA_MAX_TEXT_SIZE_BYTES = 10_485_760L;
    private static final Object ZIP_CONFIG_LOCK = new Object();
    // Eagerly initialize Tika's OOXML/AutoDetect parsers once so OOXMLParser.<clinit>()'s ZipSecureFile reset runs before we apply our strict limits.
    private static final OOXMLParser OOXML_PARSER = new OOXMLParser();
    private static final AutoDetectParser FALLBACK_PARSER = new AutoDetectParser();

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
            if (startsWithPkZipSignature(docxTempFile)) {
                try (ZipSecureFile secureZip = new ZipSecureFile(docxTempFile.toFile());
                     ZipFileZipEntrySource entrySource = new ZipFileZipEntrySource(secureZip);
                     OPCPackage opcPackage = OPCPackage.open(entrySource);
                     OPCPackageWrapper wrapper = new OPCPackageWrapper(opcPackage);
                     TikaInputStream tikaStream = TikaInputStream.get(docxTempFile, metadata)) {
                    tikaStream.setOpenContainer(wrapper);
                    OOXML_PARSER.parse(tikaStream, handler, metadata, parseContext);
                }
            } else {
                try (TikaInputStream tikaStream = TikaInputStream.get(docxTempFile, metadata)) {
                    FALLBACK_PARSER.parse(tikaStream, handler, metadata, parseContext);
                }
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

    private static boolean startsWithPkZipSignature(Path file) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            int offset = 0;
            while (offset < magic.length) {
                int read = in.read(magic, offset, magic.length - offset);
                if (read == -1) {
                    return false;
                }
                offset += read;
            }
        }
        return magic[0] == 0x50
            && magic[1] == 0x4B
            && ((magic[2] == 0x03 && magic[3] == 0x04)
                || (magic[2] == 0x05 && magic[3] == 0x06)
                || (magic[2] == 0x07 && magic[3] == 0x08));
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
        private final StringBuilder bodyBuffer = new StringBuilder();
        private final StringBuilder headingBuffer = new StringBuilder();
        private final StringBuilder cellBuffer = new StringBuilder();
        private final List<String> currentRowCells = new ArrayList<>();

        private boolean inBody;
        private int activeHeadingLevel;
        private boolean inTableCell;
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
            return List.copyOf(segments);
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
            if (!inTableCell) {
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
                currentRowCells.clear();
            } else if ("td".equals(tag) || "th".equals(tag)) {
                inTableCell = true;
                cellBuffer.setLength(0);
            } else if ("br".equals(tag)) {
                appendNewline(inTableCell ? cellBuffer : bodyBuffer);
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
                inBody = false;
                return;
            }
            if (!inBody) {
                return;
            }
            if (!inTableCell) {
                int headingLevel = parseHeadingLevel(tag);
                if (headingLevel > 0 && activeHeadingLevel == headingLevel) {
                    String cleanHeading = IngestionTextNormalizer.normalizeText(headingBuffer.toString())
                        .replace('\n', ' ');
                    headingBuffer.setLength(0);
                    activeHeadingLevel = 0;
                    if (!cleanHeading.isEmpty()) {
                        headings[headingLevel - 1] = cleanHeading;
                        Arrays.fill(headings, headingLevel, headings.length, null);
                    }
                    return;
                }
            }
            if ("td".equals(tag) || "th".equals(tag)) {
                inTableCell = false;
                String cell = IngestionTextNormalizer.normalizeText(cellBuffer.toString())
                    .replaceAll("\\s+", " ");
                cellBuffer.setLength(0);
                currentRowCells.add(cell);
                return;
            }
            if ("tr".equals(tag)) {
                if (currentRowCells.stream().anyMatch(cell -> !cell.isEmpty())) {
                    if (!bodyBuffer.isEmpty() && bodyBuffer.charAt(bodyBuffer.length() - 1) != '\n') {
                        bodyBuffer.append('\n');
                    }
                    bodyBuffer.append("| ").append(String.join(" | ", currentRowCells)).append(" |\n");
                }
                currentRowCells.clear();
                return;
            }
            if ("p".equals(tag) || "li".equals(tag) || (inTableCell && parseHeadingLevel(tag) > 0)) {
                if (inTableCell) {
                    if (!cellBuffer.isEmpty() && !Character.isWhitespace(cellBuffer.charAt(cellBuffer.length() - 1))) {
                        cellBuffer.append(' ');
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
            } else if (inTableCell) {
                cellBuffer.append(ch, start, length);
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
