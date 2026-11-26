package com.documind.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Externalized CORS configuration allowing browser clients (such as Next.js on host port 3002)
 * to call the stateless Bearer API without opening wildcard origins or cookie credentials.
 */
@ConfigurationProperties(prefix = "documind.security.cors")
public record CorsProperties(
    List<String> allowedOrigins,
    List<String> allowedMethods,
    List<String> allowedHeaders,
    List<String> exposedHeaders,
    Duration maxAge
) {

    public static final List<String> DEFAULT_ALLOWED_ORIGINS =
        List.of("http://localhost:3002", "http://127.0.0.1:3002");
    public static final List<String> DEFAULT_ALLOWED_METHODS =
        List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    public static final List<String> DEFAULT_ALLOWED_HEADERS =
        List.of("Authorization", "Content-Type", "Accept", "Range");
    public static final List<String> DEFAULT_EXPOSED_HEADERS =
        List.of("Retry-After", "WWW-Authenticate", "Content-Disposition", "Content-Range");
    public static final Duration DEFAULT_MAX_AGE = Duration.ofHours(1);

    @ConstructorBinding
    public CorsProperties {
        allowedOrigins = sanitizeOrigins(allowedOrigins, DEFAULT_ALLOWED_ORIGINS);
        allowedMethods = sanitizeList(allowedMethods, DEFAULT_ALLOWED_METHODS);
        allowedHeaders = sanitizeList(allowedHeaders, DEFAULT_ALLOWED_HEADERS);
        exposedHeaders = sanitizeList(exposedHeaders, DEFAULT_EXPOSED_HEADERS);
        maxAge = (maxAge != null && !maxAge.isNegative() && !maxAge.isZero())
            ? maxAge
            : DEFAULT_MAX_AGE;
    }

    private static List<String> sanitizeOrigins(List<String> values, List<String> fallback) {
        if (values == null) {
            return fallback;
        }
        List<String> cleaned = values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .map(item -> item.replaceAll("/+$", ""))
            .filter(item -> !item.isEmpty())
            .toList();
        for (String origin : cleaned) {
            if (origin.contains("*")) {
                throw new IllegalArgumentException("Wildcard CORS origins are not allowed: " + origin);
            }
        }
        return cleaned.isEmpty() ? fallback : List.copyOf(cleaned);
    }

    private static List<String> sanitizeList(List<String> values, List<String> fallback) {
        if (values == null) {
            return fallback;
        }
        List<String> cleaned = values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .toList();
        return cleaned.isEmpty() ? fallback : List.copyOf(cleaned);
    }
}
