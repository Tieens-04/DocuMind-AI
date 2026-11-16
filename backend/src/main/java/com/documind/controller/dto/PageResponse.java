package com.documind.controller.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Objects;

/**
 * Standardized paginated API response wrapper.
 */
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

    public PageResponse {
        items = items != null ? List.copyOf(items) : List.of();
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        Objects.requireNonNull(page, "page must not be null");
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
