package com.documind.domain.enums;

/**
 * Cache hit classification across L1 Redis exact cache and L2 pgvector semantic cache.
 */
public enum CacheTier {
    NONE,
    L1,
    L2
}
