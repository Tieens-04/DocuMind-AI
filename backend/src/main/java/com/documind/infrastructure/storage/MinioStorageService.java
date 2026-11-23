package com.documind.infrastructure.storage;

import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.InvalidDocumentRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final String FALLBACK_FILENAME = "document";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties props;

    public MinioStorageService(S3Client s3Client, S3Presigner s3Presigner, StorageProperties props) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
        this.props = Objects.requireNonNull(props, "props must not be null");
    }

    public String buildStorageKey(UUID departmentId, UUID uploadUuid, int version, String sanitizedFileName) {
        Objects.requireNonNull(departmentId, "departmentId must not be null");
        Objects.requireNonNull(uploadUuid, "uploadUuid must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (sanitizedFileName == null || sanitizedFileName.isBlank()) {
            throw new IllegalArgumentException("sanitizedFileName must not be blank");
        }
        return "departments/" + departmentId + "/" + uploadUuid + "/v" + version + "/" + sanitizedFileName;
    }

    public void uploadStream(String storageKey, InputStream inputStream, long contentLength, String contentType) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(inputStream, "inputStream must not be null");

        AwsRequestOverrideConfiguration overrideConfig = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(props.uploadTimeout())
            .apiCallAttemptTimeout(props.uploadTimeout())
            .build();

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(storageKey)
            .contentType(contentType)
            .contentLength(contentLength)
            .overrideConfiguration(overrideConfig)
            .build();

        try {
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));
        } catch (SdkException ex) {
            rethrowIfDomainStreamException(ex);
            throw new DocumentStorageException("Failed to upload object to storage key: " + storageKey, ex);
        }
    }

    public ResponseInputStream<GetObjectResponse> openStream(String storageKey) {
        return openStream(storageKey, null);
    }

    public ResponseInputStream<GetObjectResponse> openStream(String storageKey, String byteRange) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        GetObjectRequest.Builder builder = GetObjectRequest.builder()
            .bucket(props.bucket())
            .key(storageKey);
        if (byteRange != null && !byteRange.isBlank()) {
            builder.range(byteRange.trim());
        }
        try {
            return s3Client.getObject(builder.build());
        } catch (SdkException ex) {
            throw new DocumentStorageException("Failed to open stream for storage key: " + storageKey, ex);
        }
    }

    public void deleteObject(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(props.bucket())
            .key(storageKey)
            .build();
        try {
            s3Client.deleteObject(request);
        } catch (SdkException ex) {
            throw new DocumentStorageException("Failed to delete object for storage key: " + storageKey, ex);
        }
    }

    public void deleteObjectQuietly(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            deleteObject(storageKey);
        } catch (DocumentStorageException | SdkException ex) {
            log.warn("Failed to quietly delete object '{}' from bucket '{}': {}", storageKey, props.bucket(), ex.getMessage());
        }
    }

    public Duration presignedExpiry() {
        return props.presignedExpiry();
    }

    public static String appendPageFragment(String baseUrl, Integer pageNumber) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        if (pageNumber == null) {
            return baseUrl;
        }
        return baseUrl + "#page=" + pageNumber;
    }

    public URL generatePresignedGetUrl(String storageKey, String inlineFileName) {
        return generatePresignedGetUrl(storageKey, inlineFileName, props.presignedExpiry());
    }

    public URL generatePresignedGetUrl(String storageKey, String inlineFileName, Duration expiry) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Duration effectiveExpiry = expiry != null ? expiry : props.presignedExpiry();

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(props.bucket())
            .key(storageKey)
            .responseContentDisposition(buildInlineContentDisposition(inlineFileName))
            .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(effectiveExpiry)
            .getObjectRequest(getObjectRequest)
            .build();

        try {
            return s3Presigner.presignGetObject(presignRequest).url();
        } catch (SdkException ex) {
            throw new DocumentStorageException("Failed to generate presigned GET URL for storage key: " + storageKey, ex);
        }
    }

    public static String buildInlineContentDisposition(String inlineFileName) {
        String cleanedUtf8 = (inlineFileName == null)
            ? ""
            : inlineFileName.replaceAll("[\\r\\n\\u0000-\\u001F\\u007F]+", " ").trim();
        if (cleanedUtf8.isEmpty()) {
            cleanedUtf8 = FALLBACK_FILENAME;
        }

        String transliterated = Normalizer.normalize(
                cleanedUtf8.replace('đ', 'd').replace('Đ', 'D'),
                Normalizer.Form.NFD
            )
            .replaceAll("\\p{M}+", "");

        String safeAsciiName = transliterated
            .replaceAll("[\"\\\\;]", "_")
            .replaceAll("[^\\x20-\\x7E]", "_")
            .replaceAll("\\s+", " ")
            .trim();
        if (safeAsciiName.isEmpty()) {
            safeAsciiName = FALLBACK_FILENAME;
        }

        String encodedUtf8 = URLEncoder.encode(cleanedUtf8, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"" + safeAsciiName + "\"; filename*=UTF-8''" + encodedUtf8;
    }

    private static void rethrowIfDomainStreamException(Throwable ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof DocumentTooLargeException tooLarge) {
                throw tooLarge;
            }
            if (cause instanceof InvalidDocumentRequestException invalidRequest) {
                throw invalidRequest;
            }
            cause = cause.getCause();
        }
    }
}
