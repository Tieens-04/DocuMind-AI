package com.documind.controller.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for administratively resetting a user's password and revoking all active sessions.
 */
public record AdminResetPasswordRequest(
    @NotBlank(message = "New password must not be blank")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters (max 72 UTF-8 bytes)")
    String newPassword
) {
    @Override
    public String toString() {
        return "AdminResetPasswordRequest[newPassword=[REDACTED]]";
    }
}
