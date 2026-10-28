package org.example.gametopupkh.auth.passwordreset;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Completes the password reset with the single-use secret from the email. */
public record ResetPasswordRequest(

        @NotBlank(message = "token is required")
        @Size(max = 512, message = "token must be at most 512 characters")
        @Schema(example = "SECURE_RESET_TOKEN")
        String token,

        @NotBlank(message = "newPassword is required")
        @Size(min = 8, message = "newPassword must be at least 8 characters")
        @Size(max = 72, message = "newPassword must be at most 72 characters")
        @Schema(example = "NewStrongPassword123!")
        String newPassword) {
}
