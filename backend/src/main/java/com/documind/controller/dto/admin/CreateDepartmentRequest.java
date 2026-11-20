package com.documind.controller.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a new enterprise department.
 */
public record CreateDepartmentRequest(
    @NotBlank(message = "Department code must not be blank")
    @Size(min = 2, max = 32, message = "Department code must be between 2 and 32 characters")
    String code,

    @NotBlank(message = "Department name must not be blank")
    @Size(max = 120, message = "Department name must not exceed 120 characters")
    String name,

    @Size(max = 2000, message = "Department description must not exceed 2000 characters")
    String description
) {
    public CreateDepartmentRequest {
        code = code != null ? code.trim() : null;
    }
}
