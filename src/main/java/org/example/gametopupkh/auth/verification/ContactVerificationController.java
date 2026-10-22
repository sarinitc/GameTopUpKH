package org.example.gametopupkh.auth.verification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
public class ContactVerificationController {

    private final ContactVerificationService verificationService;

    public ContactVerificationController(ContactVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/verify-email/request")
    @Operation(summary = "Request an email verification code",
            description = "Public endpoint. Returns the same accepted response for unknown, verified, "
                    + "blocked or cooldown contacts. Codes expire after 10 minutes and are never returned in the response.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Request accepted")
    public ResponseEntity<ApiResponse<Void>> requestEmailCode(
            @Validated @RequestBody EmailVerificationRequest request) {
        verificationService.requestCode(VerificationChannel.EMAIL, request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok());
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an email verification code",
            description = "Public endpoint. Consumes the latest code and marks only the registered email as verified. "
                    + "A code allows at most five failed attempts; invalid or expired codes return INVALID_VERIFICATION_CODE.")
    public ResponseEntity<ApiResponse<VerificationResponse>> verifyEmail(
            @Validated @RequestBody EmailVerificationConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(verificationService.verifyCode(
                VerificationChannel.EMAIL, request.email(), request.code())));
    }

    @PostMapping("/verify-phone/request")
    @Operation(summary = "Request a phone verification code",
            description = "Public endpoint. Returns the same accepted response for unknown, verified, "
                    + "blocked or cooldown contacts. Codes expire after 10 minutes and are never returned in the response.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Request accepted")
    public ResponseEntity<ApiResponse<Void>> requestPhoneCode(
            @Validated @RequestBody PhoneVerificationRequest request) {
        verificationService.requestCode(VerificationChannel.PHONE, request.phoneNumber());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok());
    }

    @PostMapping("/verify-phone")
    @Operation(summary = "Confirm a phone verification code",
            description = "Public endpoint. Consumes the latest code and marks only the registered phone as verified. "
                    + "A code allows at most five failed attempts; invalid or expired codes return INVALID_VERIFICATION_CODE.")
    public ResponseEntity<ApiResponse<VerificationResponse>> verifyPhone(
            @Validated @RequestBody PhoneVerificationConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(verificationService.verifyCode(
                VerificationChannel.PHONE, request.phoneNumber(), request.code())));
    }
}
