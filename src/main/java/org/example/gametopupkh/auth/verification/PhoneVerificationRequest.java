package org.example.gametopupkh.auth.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Requests a verification code for an account's registered phone number.")
public record PhoneVerificationRequest(
        @NotBlank(message = "phoneNumber is required")
        @Pattern(regexp = "^\\+[1-9][0-9]{1,14}$",
                message = "phoneNumber must use the international format, for example +85512345678")
        @Size(max = 20, message = "phoneNumber must be at most 20 characters")
        @Schema(description = "Phone number already registered on the account, in international format.",
                example = "+85512345678")
        String phoneNumber) {
}
