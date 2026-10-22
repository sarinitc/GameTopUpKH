package org.example.gametopupkh.auth.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Requests a verification code for an account's registered email address.")
public record EmailVerificationRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        @Schema(description = "Email address already registered on the account.",
                example = "player@example.com")
        String email) {
}
