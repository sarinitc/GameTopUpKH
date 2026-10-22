package org.example.gametopupkh.auth.verification;

public record VerificationResponse(boolean emailVerified, boolean phoneVerified) {
}
