package com.documind.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts normalized client IP addresses strictly from {@link HttpServletRequest#getRemoteAddr()}
 * (relying on container {@code forward-headers-strategy=native} for trusted reverse proxies)
 * and bounds values to the 45-character database column limit.
 */
public final class ClientIpResolver {

    public static final int MAX_IP_LENGTH = 45;

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return normalize(request.getRemoteAddr());
    }

    public static String normalize(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return null;
        }
        String trimmed = rawIp.trim();
        return trimmed.length() > MAX_IP_LENGTH ? trimmed.substring(0, MAX_IP_LENGTH) : trimmed;
    }
}
