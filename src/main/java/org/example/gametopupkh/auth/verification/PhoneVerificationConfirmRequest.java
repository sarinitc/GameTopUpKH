package org.example.gametopupkh.auth.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Confirms ownership of the registered phone number using its latest code.")
public record PhoneVerificationConfirmRequest(
        @NotBlank(message = "phoneNumber is required")
        @Pattern(regexp = "^\\+[1-9][0-9]{1,14}$",
                message = "phoneNumber must use the international format, for example +85512345678")
        @Size(max = 20, message = "phoneNumber must be at most 20 characters")
        @Schema(description = "Phone number to which the verification code was sent, in international format.",
                example = "+85512345678")
        String phoneNumber,

        @NotBlank(message = "code is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "code must contain exactly 6 digits")
        @Schema(description = "Latest six-digit code, including any leading zeroes.",
                example = "012345")
        String code) {
}
