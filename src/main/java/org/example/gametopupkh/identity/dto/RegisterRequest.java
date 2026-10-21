package org.example.gametopupkh.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload of the registration endpoint. At least one contact channel is required,
 * which the service checks before the user is stored.
 */
public record RegisterRequest(

        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @Pattern(regexp = "^\\+[1-9][0-9]{1,14}$",
                message = "phoneNumber must use the international format, for example +85512345678")
        @Size(max = 20, message = "phoneNumber must be at most 20 characters")
        String phoneNumber,

        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        @Size(max = 72, message = "password must be at most 72 characters")
        String password) {
}
