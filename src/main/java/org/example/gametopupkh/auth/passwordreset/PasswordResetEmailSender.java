package org.example.gametopupkh.auth.passwordreset;

import java.time.Instant;

/**
 * Delivers password reset instructions and the follow-up password-change notice.
 * Implementations must never log or return the raw reset token.
 */
public interface PasswordResetEmailSender {

    /**
     * Fails before any account work when delivery is not configured. The service catches
     * this and still returns the generic response, so account existence is not revealed.
     */
    default void checkAvailability() {
    }

    void sendReset(String destination, String resetToken, String resetLink, Instant expiresAt);

    void sendPasswordChanged(String destination);
}
