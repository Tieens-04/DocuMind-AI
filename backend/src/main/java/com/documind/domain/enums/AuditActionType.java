package com.documind.domain.enums;

/**
 * Canonical audit event types recorded for governance, cost tracking, and rate limiting.
 */
public enum AuditActionType {
    CHAT_QUERY,
    DOC_UPLOAD,
    DOC_REINDEX,
    DOC_DELETE,
    AUTH_LOGIN,
    AUTH_LOGOUT,
    RATE_LIMITED
}
