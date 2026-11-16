package com.documind.service.document;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Seam for resolving caller identity, department, role, and client IP across dev fallback and JWT security.
 */
public interface CallerContextResolver {

    CallerContext resolve(HttpServletRequest request);
}
