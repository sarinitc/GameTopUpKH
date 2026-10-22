package org.example.gametopupkh.auth.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Confirms ownership of the registered email address using its latest code.")
public record EmailVerificationConfirmRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        @Schema(description = "Email address to which the verification code was sent.",
                example = "player@example.com")
        String email,

        @NotBlank(message = "code is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "code must contain exactly 6 digits")
        @Schema(description = "Latest six-digit code, including any leading zeroes.",
                example = "012345")
        String code) {
}
