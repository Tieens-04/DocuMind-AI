package com.documind.service.document;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * Seam for resolving caller department, user, and client IP across dev fallback and future JWT security.
 */
public interface CallerContextResolver {

    CallerContext resolve(UUID requestDepartmentId, HttpServletRequest request);
}
