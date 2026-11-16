package com.documind.infrastructure.storage;

import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioStorageServiceTest {

    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private StorageProperties storageProperties;
    private MinioStorageService minioStorageService;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Presigner = mock(S3Presigner.class);
        storageProperties = new StorageProperties(
            "http://localhost:9000",
            "documind_minio",
            "documind_minio_secret",
            "documind-documents",
            "us-east-1",
            Duration.ofSeconds(60),
            Duration.ofMinutes(15)
        );
        minioStorageService = new MinioStorageService(s3Client, s3Presigner, storageProperties);
    }

    @Test
    void storagePropertiesDefaultsUploadTimeoutAndPresignedExpiryWhenNullOrFiveArg() {
        var fiveArg = new StorageProperties("http://localhost:9000", "ak", "sk", "documind-documents", "us-east-1");
        assertThat(fiveArg.uploadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(fiveArg.presignedExpiry()).isEqualTo(Duration.ofMinutes(15));

        var sevenArgWithNulls = new StorageProperties(
            "http://localhost:9000", "ak", "sk", "documind-documents", "us-east-1", null, null
        );
        assertThat(sevenArgWithNulls.uploadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(sevenArgWithNulls.presignedExpiry()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void buildStorageKeyFormatsExpectedPath() {
        UUID deptId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID uploadId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        String key = minioStorageService.buildStorageKey(deptId, uploadId, 1, "quy_che.pdf");

        assertThat(key).isEqualTo("departments/" + deptId + "/" + uploadId + "/v1/quy_che.pdf");
    }

    @Test
    void uploadStreamSendsPutObjectWithBucketContentTypeLengthAnd60SecondOverrideTimeouts() throws IOException {
        byte[] content = "PDF-DATA".getBytes(StandardCharsets.UTF_8);
        String storageKey = "departments/dept-1/upload-1/v1/quy_che.pdf";
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().eTag("\"etag-1\"").build());

        minioStorageService.uploadStream(
            storageKey,
            new ByteArrayInputStream(content),
            content.length,
            "application/pdf"
        );

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("documind-documents");
        assertThat(request.key()).isEqualTo(storageKey);
        assertThat(request.contentType()).isEqualTo("application/pdf");
        assertThat(request.contentLength()).isEqualTo((long) content.length);
        assertThat(request.overrideConfiguration()).isPresent();
        assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout())
            .contains(Duration.ofSeconds(60));
        assertThat(request.overrideConfiguration().orElseThrow().apiCallAttemptTimeout())
            .contains(Duration.ofSeconds(60));

        RequestBody body = bodyCaptor.getValue();
        assertThat(body.optionalContentLength()).contains((long) content.length);
        try (InputStream bodyStream = body.contentStreamProvider().newStream()) {
            assertThat(bodyStream.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void uploadStreamWrapsSdkExceptionInDocumentStorageException() {
        SdkClientException sdkFailure = SdkClientException.create("Connection refused");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(sdkFailure);

        assertThatThrownBy(() -> minioStorageService.uploadStream(
            "departments/dept-1/upload-1/v1/quy_che.pdf",
            new ByteArrayInputStream(new byte[]{1, 2, 3}),
            3L,
            "application/pdf"
        ))
            .isInstanceOf(DocumentStorageException.class)
            .hasCause(sdkFailure);
    }

    @Test
    void uploadStreamUnwrapsDocumentTooLargeExceptionFromSdkExceptionCauseChain() {
        DocumentTooLargeException tooLarge = new DocumentTooLargeException(10L, "Too large");
        SdkClientException wrappedBySdk = SdkClientException.create("Unable to execute HTTP request", tooLarge);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(wrappedBySdk);

        assertThatThrownBy(() -> minioStorageService.uploadStream(
            "departments/dept-1/upload-1/v1/oversized.pdf",
            new ByteArrayInputStream(new byte[]{1, 2, 3}),
            3L,
            "application/pdf"
        ))
            .isSameAs(tooLarge);
    }

    @Test
    void generatePresignedGetUrlBuildsSafeInlineContentDispositionAndUses15MinuteExpiry() throws Exception {
        String storageKey = "departments/dept-1/upload-1/v1/quy_che.pdf";
        URL expectedUrl = URI.create("http://localhost:9000/documind-documents/" + storageKey + "?X-Amz-Expires=900").toURL();

        PresignedGetObjectRequest presignedResponse = mock(PresignedGetObjectRequest.class);
        when(presignedResponse.url()).thenReturn(expectedUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedResponse);

        URL actualUrl = minioStorageService.generatePresignedGetUrl(
            storageKey,
            "Quy chế \"nhân sự\"\r\n2026.pdf"
        );

        assertThat(actualUrl).isEqualTo(expectedUrl);

        ArgumentCaptor<GetObjectPresignRequest> presignCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(presignCaptor.capture());

        GetObjectPresignRequest captured = presignCaptor.getValue();
        assertThat(captured.signatureDuration()).isEqualTo(Duration.ofMinutes(15));

        GetObjectRequest getRequest = captured.getObjectRequest();
        assertThat(getRequest.bucket()).isEqualTo("documind-documents");
        assertThat(getRequest.key()).isEqualTo(storageKey);

        String disposition = getRequest.responseContentDisposition();
        assertThat(disposition)
            .startsWith("inline; filename=\"")
            .doesNotContain("\r")
            .doesNotContain("\n");

        int firstQuote = disposition.indexOf('"');
        int secondQuote = disposition.indexOf('"', firstQuote + 1);
        assertThat(secondQuote).isGreaterThan(firstQuote);
        String asciiFallback = disposition.substring(firstQuote + 1, secondQuote);
        assertThat(asciiFallback)
            .isNotBlank()
            .matches("[\\x20-\\x7E]+")
            .doesNotContain("\"")
            .doesNotContain("\\");
    }

    @Test
    void generatePresignedGetUrlWithCustomExpiryUsesProvidedDuration() throws Exception {
        String storageKey = "departments/dept-1/upload-1/v1/quy_che.pdf";
        URL expectedUrl = URI.create("http://localhost:9000/documind-documents/" + storageKey + "?X-Amz-Expires=300").toURL();

        PresignedGetObjectRequest presignedResponse = mock(PresignedGetObjectRequest.class);
        when(presignedResponse.url()).thenReturn(expectedUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedResponse);

        URL actualUrl = minioStorageService.generatePresignedGetUrl(storageKey, "quy_che.pdf", Duration.ofMinutes(5));

        assertThat(actualUrl).isEqualTo(expectedUrl);
        ArgumentCaptor<GetObjectPresignRequest> presignCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(presignCaptor.capture());
        assertThat(presignCaptor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void openStreamReturnsResponseInputStreamAndWrapsSdkException() {
        String storageKey = "departments/dept-1/upload-1/v1/quy_che.pdf";
        ResponseInputStream<GetObjectResponse> expectedStream = new ResponseInputStream<>(
            GetObjectResponse.builder().contentLength(4L).build(),
            AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(expectedStream);

        ResponseInputStream<GetObjectResponse> actual = minioStorageService.openStream(storageKey);

        assertThat(actual).isSameAs(expectedStream);
        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("documind-documents");
        assertThat(captor.getValue().key()).isEqualTo(storageKey);

        SdkClientException sdkFailure = SdkClientException.create("Read timeout");
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(sdkFailure);
        assertThatThrownBy(() -> minioStorageService.openStream(storageKey))
            .isInstanceOf(DocumentStorageException.class)
            .hasCause(sdkFailure);
    }

    @Test
    void deleteObjectQuietlySwallowsSdkExceptionWhileDeleteObjectWrapsIt() {
        String storageKey = "departments/dept-1/upload-1/v1/orphan.pdf";
        SdkClientException sdkFailure = SdkClientException.create("S3 unreachable");
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(sdkFailure);

        assertThatCode(() -> minioStorageService.deleteObjectQuietly(storageKey))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> minioStorageService.deleteObject(storageKey))
            .isInstanceOf(DocumentStorageException.class)
            .hasCause(sdkFailure);

        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
            .thenReturn(DeleteObjectResponse.builder().build());
        assertThatCode(() -> minioStorageService.deleteObject(storageKey))
            .doesNotThrowAnyException();
    }
}
