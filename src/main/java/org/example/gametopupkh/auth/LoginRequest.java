package org.example.gametopupkh.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload of the login endpoint. The identifier is either the registered email
 * address or the registered phone number.
 */
public record LoginRequest(

        @NotBlank(message = "identifier is required")
        @Size(max = 255, message = "identifier must be at most 255 characters")
        String identifier,

        @NotBlank(message = "password is required")
        @Size(max = 72, message = "password must be at most 72 characters")
        String password) {
}
