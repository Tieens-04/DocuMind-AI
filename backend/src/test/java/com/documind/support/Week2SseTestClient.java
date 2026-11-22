package com.documind.support;

import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.service.rag.RagConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared HTTP/1.1 REST, multipart, MinIO presigned fetch, and RFC 8895 SSE frame client utilities
 * for Milestone 2 ({@code DA-18}) real-JWT Testcontainers integration suites.
 */
public final class Week2SseTestClient implements AutoCloseable {

    public static final String SEED_DEV_PASSWORD = "DocuMind@2026!";
    public static final String ADMIN_EMAIL = "admin@documind.ai";
    public static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    public static final String IT_MANAGER_EMAIL = "manager.it@documind.ai";
    public static final String SALES_MANAGER_EMAIL = "manager.sales@documind.ai";
    public static final String HR_EMPLOYEE_EMAIL = "employee.hr@documind.ai";
    public static final String IT_EMPLOYEE_EMAIL = "employee.it@documind.ai";
    public static final String SALES_EMPLOYEE_EMAIL = "employee.sales@documind.ai";
    public static final List<String> ALL_SEVEN_ACTOR_EMAILS = List.of(
        ADMIN_EMAIL,
        HR_MANAGER_EMAIL,
        IT_MANAGER_EMAIL,
        SALES_MANAGER_EMAIL,
        HR_EMPLOYEE_EMAIL,
        IT_EMPLOYEE_EMAIL,
        SALES_EMPLOYEE_EMAIL
    );

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public Week2SseTestClient(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        // HTTP/1.1 avoids h2c stream multiplexing interference when 25-30 SSE streams stay open or abort concurrently
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .build();
    }

    public AuthTokenResponse login(int port, String email, String password) throws Exception {
        HttpResponse<String> resp = sendJson(
            port,
            "POST",
            "/api/v1/auth/login",
            Map.of("email", email, "password", password),
            null
        );
        assertThat(resp.statusCode())
            .as("Login failed for %s: status=%d body=%s", email, resp.statusCode(), resp.body())
            .isEqualTo(200);
        return objectMapper.readValue(resp.body(), AuthTokenResponse.class);
    }

    public Map<String, String> loginAllSevenActors(int port) throws Exception {
        Map<String, String> tokens = new HashMap<>();
        for (String email : ALL_SEVEN_ACTOR_EMAILS) {
            tokens.put(email, login(port, email, SEED_DEV_PASSWORD).accessToken());
        }
        return tokens;
    }

    public HttpResponse<String> sendJson(
        int port,
        String method,
        String path,
        Object body,
        String bearerToken
    ) throws Exception {
        Map<String, String> headers = bearerToken != null
            ? Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            : Map.of();
        return sendJsonWithHeaders(port, method, path, body, headers);
    }

    public HttpResponse<String> sendJsonWithHeaders(
        int port,
        String method,
        String path,
        Object body,
        Map<String, String> extraHeaders
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(port, path))
            .timeout(DEFAULT_REQUEST_TIMEOUT);
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        HttpRequest.BodyPublisher publisher;
        if (body != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            publisher = HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body),
                StandardCharsets.UTF_8
            );
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        HttpRequest request = builder.method(method, publisher).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public HttpResponse<String> sendMultipart(
        int port,
        String method,
        String path,
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        UUID departmentId,
        String bearerToken
    ) throws Exception {
        String boundary = "----DocuMindWeek2Boundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = buildMultipartBody(boundary, filename, contentType, content, accessLevel, departmentId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(port, path))
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public DocumentResponse uploadAndAwaitCompleted(
        int port,
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        UUID targetDepartmentId,
        String bearerToken
    ) throws Exception {
        HttpResponse<String> resp = sendMultipart(
            port,
            "POST",
            "/api/v1/documents/upload",
            filename,
            contentType,
            content,
            accessLevel,
            targetDepartmentId,
            bearerToken
        );
        assertThat(resp.statusCode())
            .as("Upload failed for %s: status=%d body=%s", filename, resp.statusCode(), resp.body())
            .isEqualTo(202);
        DocumentResponse doc = objectMapper.readValue(resp.body(), DocumentResponse.class);
        DocumentStatusResponse terminal = awaitTerminalDocumentStatus(port, doc.id(), bearerToken, Duration.ofSeconds(20));
        assertThat(terminal.status())
            .as("Expected COMPLETED for %s but got %s (error=%s)", filename, terminal.status(), terminal.errorMessage())
            .isEqualTo(DocumentStatus.COMPLETED);
        assertThat(terminal.chunkCount()).isGreaterThan(0);
        return doc;
    }

    public HttpResponse<InputStream> openSseStream(
        int port,
        Map<String, Object> body,
        String bearerToken
    ) throws Exception {
        return openSseStream(httpClient, port, body, bearerToken);
    }

    /**
     * Opens an SSE stream on an isolated {@link HttpClient} so {@link HttpClient#shutdownNow()} tears down
     * the underlying TCP socket immediately (deterministic {@code AbortController} emulation).
     */
    public DedicatedSseConnection openDedicatedSseStream(
        int port,
        Map<String, Object> body,
        String bearerToken
    ) throws Exception {
        HttpClient dedicatedClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .build();
        try {
            HttpResponse<InputStream> response = openSseStream(dedicatedClient, port, body, bearerToken);
            return new DedicatedSseConnection(dedicatedClient, response);
        } catch (Exception ex) {
            dedicatedClient.shutdownNow();
            throw ex;
        }
    }

    public List<SseFrame> collectAllSseFrames(
        int port,
        Map<String, Object> body,
        String bearerToken
    ) throws Exception {
        HttpResponse<InputStream> response = openSseStream(port, body, bearerToken);
        try (InputStream raw = response.body()) {
            if (response.statusCode() != 200) {
                String errorBody = new String(raw.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(response.statusCode())
                    .as("Expected 200 text/event-stream for %s, got status=%d body=%s", body, response.statusCode(), errorBody)
                    .isEqualTo(200);
            }
            return collectAllFrames(raw);
        }
    }

    public List<SseFrame> collectAllFrames(InputStream raw) throws IOException {
        List<SseFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            SseFrame frame;
            while ((frame = readNextFrame(reader)) != null) {
                frames.add(frame);
            }
        }
        return frames;
    }

    public static BufferedReader openUtf8Reader(HttpResponse<InputStream> response) {
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    public static SseFrame readNextFrame(BufferedReader reader) throws IOException {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventName != null || !data.isEmpty()) {
                    return new SseFrame(eventName, data.toString());
                }
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                data.append(value);
            }
        }
        if (eventName != null || !data.isEmpty()) {
            return new SseFrame(eventName, data.toString());
        }
        return null;
    }

    public HttpResponse<byte[]> fetchRawBytes(URI url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url)
            .GET()
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    public DocumentStatusResponse awaitTerminalDocumentStatus(
        int port,
        UUID documentId,
        String bearerToken,
        Duration timeout
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        DocumentStatusResponse last = null;
        while (System.nanoTime() < deadlineNanos) {
            HttpResponse<String> resp = sendJson(port, "GET", "/api/v1/documents/" + documentId + "/status", null, bearerToken);
            assertThat(resp.statusCode())
                .as("Failed to poll document status %s: %s", documentId, resp.body())
                .isEqualTo(200);
            last = objectMapper.readValue(resp.body(), DocumentStatusResponse.class);
            if (last.status() == DocumentStatus.COMPLETED || last.status() == DocumentStatus.FAILED) {
                return last;
            }
            Thread.sleep(40L);
        }
        throw new AssertionError("Timed out awaiting terminal ingestion status for " + documentId + "; last=" + last);
    }

    public void assertDeterministicSseRefusal(
        List<SseFrame> frames,
        String forbiddenPhrase,
        String forbiddenFileName,
        boolean expectZeroTotalTokens
    ) throws Exception {
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).event()).isEqualTo("citations");
        JsonNode citationsNode = objectMapper.readTree(frames.get(0).data()).path("citations");
        assertThat(citationsNode.isArray()).as("citations field must be a JSON array").isTrue();
        assertThat(citationsNode).isEmpty();

        assertThat(frames.get(1).event()).isEqualTo("token");
        String delta = objectMapper.readTree(frames.get(1).data()).path("delta").asText();
        assertThat(delta).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);

        assertThat(frames.get(2).event()).isEqualTo("done");
        JsonNode doneJson = objectMapper.readTree(frames.get(2).data());
        assertThat(doneJson.path("abstained").asBoolean()).isTrue();
        if (expectZeroTotalTokens) {
            assertThat(doneJson.path("tokenUsage").path("promptTokens").asInt()).isZero();
            assertThat(doneJson.path("tokenUsage").path("completionTokens").asInt()).isZero();
            assertThat(doneJson.path("tokenUsage").path("totalTokens").asInt()).isZero();
        }

        String joinedPayloads = frames.stream().map(SseFrame::data).reduce("", (a, b) -> a + "\n" + b);
        String normalizedPayloads = AbstractIntegrationTest.normalizeNfcLower(joinedPayloads);
        if (forbiddenPhrase != null && !forbiddenPhrase.isBlank()) {
            assertThat(normalizedPayloads).doesNotContain(AbstractIntegrationTest.normalizeNfcLower(forbiddenPhrase));
        }
        if (forbiddenFileName != null && !forbiddenFileName.isBlank()) {
            assertThat(normalizedPayloads).doesNotContain(AbstractIntegrationTest.normalizeNfcLower(forbiddenFileName));
        }
    }

    public static String stripFragment(String url) {
        int hashIndex = url.indexOf('#');
        return hashIndex >= 0 ? url.substring(0, hashIndex) : url;
    }

    @Override
    public void close() {
        // shutdownNow() aborts any leaked chunked SSE stream instead of blocking up to 1 day in close()
        httpClient.shutdownNow();
    }

    private HttpResponse<InputStream> openSseStream(
        HttpClient client,
        int port,
        Map<String, Object> body,
        String bearerToken
    ) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(port, "/api/v1/chat/stream"))
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static byte[] buildMultipartBody(
        String boundary,
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        UUID departmentId
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (accessLevel != null) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Disposition: form-data; name=\"accessLevel\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write((accessLevel.name() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (departmentId != null) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Disposition: form-data; name=\"departmentId\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write((departmentId + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public record SseFrame(String event, String data) {
    }

    public record DedicatedSseConnection(HttpClient client, HttpResponse<InputStream> response) implements AutoCloseable {
        @Override
        public void close() {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Socket may already be reset
            } finally {
                client.shutdownNow();
            }
        }
    }
}
