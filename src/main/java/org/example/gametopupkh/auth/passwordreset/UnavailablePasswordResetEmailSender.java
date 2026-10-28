package org.example.gametopupkh.auth.passwordreset;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Fails closed until a real email implementation is configured. The caller converts
 * this into the same generic response used for unknown accounts, so delivery outages
 * cannot be used for account enumeration.
 */
@Service
@Profile("!dev")
public class UnavailablePasswordResetEmailSender implements PasswordResetEmailSender {

    @Override
    public void checkAvailability() {
        throw new IllegalStateException("Password reset email delivery is not configured");
    }

    @Override
    public void sendReset(String destination, String resetToken, String resetLink, Instant expiresAt) {
        checkAvailability();
    }

    @Override
    public void sendPasswordChanged(String destination) {
        checkAvailability();
    }
}
