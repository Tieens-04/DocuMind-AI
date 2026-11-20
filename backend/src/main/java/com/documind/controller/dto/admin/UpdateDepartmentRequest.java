package com.documind.controller.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Partial update payload for modifying department code, name, or description.
 * Omitting {@code description} (or sending {@code null}) preserves the existing value;
 * sending an empty or blank string clears {@code description} to {@code null}.
 */
public record UpdateDepartmentRequest(
    @Size(min = 2, max = 32, message = "Department code must be between 2 and 32 characters")
    String code,

    @Size(max = 120, message = "Department name must not exceed 120 characters")
    String name,

    @Schema(description = "Department description. Omit or pass null to keep existing value; pass empty string \"\" to clear.")
    @Size(max = 2000, message = "Department description must not exceed 2000 characters")
    String description
) {
    public UpdateDepartmentRequest {
        code = code != null ? code.trim() : null;
    }
}
