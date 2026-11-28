package com.documind.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeHardeningTest {

    private static final Pattern DIGEST_PINNED_CHAINGUARD =
        Pattern.compile("^\\s*image:\\s*cgr\\.dev/chainguard/[\\w-]+(?::[\\w.-]+)?@sha256:[0-9a-f]{64}\\s*$");
    private static final Pattern MINIO_INIT_HEADER =
        Pattern.compile("(?m)^  minio-init:\\r?\\n");
    private static final Pattern NEXT_TOP_LEVEL_OR_SERVICE =
        Pattern.compile("\\r?\\n(?:volumes:|  [a-z0-9-]+:)");

    @Test
    void chainguardImagesArePinnedBySha256Digest() throws IOException {
        List<String> chainguardLines = Files.readAllLines(resolveRepoPath("docker-compose.yml"), StandardCharsets.UTF_8)
            .stream()
            .filter(line -> line.contains("cgr.dev/chainguard/"))
            .toList();

        assertThat(chainguardLines).hasSize(2);
        assertThat(chainguardLines).allSatisfy(line ->
            assertThat(line).matches(DIGEST_PINNED_CHAINGUARD)
        );
    }

    @Test
    void minioInitPassesCredentialsViaEnvironmentNotInlineShellExpansion() throws IOException {
        String compose = Files.readString(resolveRepoPath("docker-compose.yml"), StandardCharsets.UTF_8);
        Matcher headerMatcher = MINIO_INIT_HEADER.matcher(compose);
        assertThat(headerMatcher.find()).isTrue();

        int minioInitIndex = headerMatcher.start();
        String afterHeader = compose.substring(headerMatcher.end());
        Matcher boundary = NEXT_TOP_LEVEL_OR_SERVICE.matcher(afterHeader);
        String minioInitSection = boundary.find()
            ? compose.substring(minioInitIndex, headerMatcher.end() + boundary.start())
            : compose.substring(minioInitIndex);

        assertThat(minioInitSection)
            .contains("MINIO_ROOT_USER: ${MINIO_ROOT_USER:-documind_minio}")
            .contains("MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-documind_minio_secret}")
            .contains("MINIO_BUCKET: ${MINIO_BUCKET:-documind-documents}")
            .contains("\"$$MINIO_ROOT_USER\"")
            .contains("\"$$MINIO_ROOT_PASSWORD\"")
            .contains("\"local/$$MINIO_BUCKET\"");

        int entrypointIndex = minioInitSection.indexOf("entrypoint:");
        assertThat(entrypointIndex).isGreaterThan(0);
        String entrypointScript = minioInitSection.substring(entrypointIndex);
        assertThat(entrypointScript)
            .doesNotContain("${MINIO_ROOT_USER")
            .doesNotContain("${MINIO_ROOT_PASSWORD")
            .doesNotContain("${MINIO_BUCKET");
    }

    @Test
    void dockerfilesAndComposeEnforceFailClosedAndHardeningContracts() throws IOException {
        String frontendDockerfile = Files.readString(resolveRepoPath("frontend/Dockerfile"), StandardCharsets.UTF_8);
        assertThat(frontendDockerfile)
            .contains("ARG NEXT_PUBLIC_DEMO_ACCOUNTS=false")
            .doesNotContain("ARG NEXT_PUBLIC_DEMO_ACCOUNTS=true");

        String backendDockerfile = Files.readString(resolveRepoPath("backend/Dockerfile"), StandardCharsets.UTF_8);
        assertThat(backendDockerfile)
            .contains("./mvnw")
            .contains("-XX:MaxRAMPercentage=55.0")
            .contains("\\\"$@\\\"")
            .doesNotContain("-XX:+UseContainerSupport");

        String compose = Files.readString(resolveRepoPath("docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose)
            .contains("stop_grace_period: 35s")
            .contains("\"-O\", \"/dev/null\"");

        List<String> backendDockerignore = Files.readAllLines(
            resolveRepoPath("backend/.dockerignore"),
            StandardCharsets.UTF_8
        );
        assertThat(backendDockerignore)
            .contains("**/.env", "**/.env.*", "**/*.log", "Dockerfile", ".dockerignore")
            .doesNotContain(".mvn/")
            .doesNotContain("mvnw");

        List<String> frontendDockerignore = Files.readAllLines(
            resolveRepoPath("frontend/.dockerignore"),
            StandardCharsets.UTF_8
        );
        assertThat(frontendDockerignore)
            .contains("**/.env", "**/.env.*", "**/*.log", "Dockerfile", ".dockerignore", "out/");
    }

    private static Path resolveRepoPath(String relativeFromRoot) {
        Path fromBackendDir = Path.of("..").resolve(relativeFromRoot);
        if (Files.exists(fromBackendDir)) {
            return fromBackendDir;
        }
        return Path.of(relativeFromRoot);
    }
}
