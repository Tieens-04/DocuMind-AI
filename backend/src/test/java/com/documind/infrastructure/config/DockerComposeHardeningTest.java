package com.documind.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeHardeningTest {

    private static final Pattern DIGEST_PINNED_CHAINGUARD =
        Pattern.compile("^\\s*image:\\s*cgr\\.dev/chainguard/[\\w-]+(?::[\\w.-]+)?@sha256:[0-9a-f]{64}\\s*$");

    @Test
    void chainguardImagesArePinnedBySha256Digest() throws IOException {
        List<String> chainguardLines = Files.readAllLines(resolveComposePath(), StandardCharsets.UTF_8).stream()
            .filter(line -> line.contains("cgr.dev/chainguard/"))
            .toList();

        assertThat(chainguardLines).hasSize(2);
        assertThat(chainguardLines).allSatisfy(line ->
            assertThat(line).matches(DIGEST_PINNED_CHAINGUARD)
        );
    }

    @Test
    void minioInitPassesCredentialsViaEnvironmentNotInlineShellExpansion() throws IOException {
        String compose = Files.readString(resolveComposePath(), StandardCharsets.UTF_8);
        int minioInitIndex = compose.indexOf("  minio-init:");
        assertThat(minioInitIndex).isGreaterThanOrEqualTo(0);

        String minioInitSection = compose.substring(minioInitIndex);
        int volumesIndex = minioInitSection.indexOf("\nvolumes:");
        if (volumesIndex > 0) {
            minioInitSection = minioInitSection.substring(0, volumesIndex);
        }

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

    private static Path resolveComposePath() {
        Path fromBackendDir = Path.of("..", "docker-compose.yml");
        if (Files.exists(fromBackendDir)) {
            return fromBackendDir;
        }
        return Path.of("docker-compose.yml");
    }
}
