package org.example.gametopupkh.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload of the refresh and logout endpoints.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken is required")
        String refreshToken) {
}
