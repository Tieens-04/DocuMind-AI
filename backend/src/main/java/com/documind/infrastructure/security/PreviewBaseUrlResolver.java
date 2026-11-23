package com.documind.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Resolves the public-facing backend origin ({@code scheme://host[:port][contextPath]}) for signed
 * document preview/citation download URLs across synchronous HTTP threads, async SSE Virtual Threads,
 * reverse-proxy forwarded headers, and random-port integration servers.
 */
@Component
public class PreviewBaseUrlResolver {

    private static final ThreadLocal<String> SCOPED_BASE_URL = new ThreadLocal<>();

    private final String configuredPublicBaseUrl;
    private final int configuredServerPort;
    private final AtomicInteger initializedServerPort = new AtomicInteger(0);

    @Autowired
    public PreviewBaseUrlResolver(
        @Value("${documind.storage.preview-public-base-url:${PUBLIC_BASE_URL:}}") String configuredPublicBaseUrl,
        @Value("${server.port:8081}") int configuredServerPort
    ) {
        this.configuredPublicBaseUrl = normalizeOptionalBaseUrl(configuredPublicBaseUrl);
        this.configuredServerPort = configuredServerPort > 0 ? configuredServerPort : 8081;
    }

    public static PreviewBaseUrlResolver fixed(String baseUrl) {
        return new PreviewBaseUrlResolver(Objects.requireNonNull(baseUrl, "baseUrl must not be null"), 8081);
    }

    @EventListener(WebServerInitializedEvent.class)
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        if (event != null && event.getWebServer() != null && event.getWebServer().getPort() > 0) {
            initializedServerPort.set(event.getWebServer().getPort());
        }
    }

    public String captureCurrentBaseUrl() {
        return resolveBaseUrl();
    }

    public static String captureCurrentRequestOriginOrNull() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttrs) {
            String origin = stripTrailingSlashes(
                ServletUriComponentsBuilder.fromContextPath(servletAttrs.getRequest()).build().toUriString()
            );
            return origin.isBlank() ? null : origin;
        }
        return SCOPED_BASE_URL.get();
    }

    public static <T> T callWithBaseUrl(String baseUrl, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        String previous = SCOPED_BASE_URL.get();
        if (baseUrl != null && !baseUrl.isBlank()) {
            SCOPED_BASE_URL.set(stripTrailingSlashes(baseUrl.trim()));
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SCOPED_BASE_URL.remove();
            } else {
                SCOPED_BASE_URL.set(previous);
            }
        }
    }

    public static void runWithBaseUrl(String baseUrl, Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        callWithBaseUrl(baseUrl, () -> {
            action.run();
            return null;
        });
    }

    public String resolveBaseUrl() {
        if (configuredPublicBaseUrl != null) {
            return configuredPublicBaseUrl;
        }
        String scoped = SCOPED_BASE_URL.get();
        if (scoped != null && !scoped.isBlank()) {
            return scoped;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            String fromRequest = stripTrailingSlashes(
                ServletUriComponentsBuilder.fromContextPath(request).build().toUriString()
            );
            if (!fromRequest.isBlank()) {
                return fromRequest;
            }
        }
        int activePort = initializedServerPort.get();
        int effectivePort = activePort > 0 ? activePort : configuredServerPort;
        return "http://localhost:" + effectivePort;
    }

    private static String normalizeOptionalBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String stripped = stripTrailingSlashes(raw.trim());
        return stripped.isEmpty() ? null : stripped;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
