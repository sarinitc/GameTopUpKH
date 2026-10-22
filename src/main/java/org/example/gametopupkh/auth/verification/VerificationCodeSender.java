package org.example.gametopupkh.auth.verification;

/** Delivers a code without exposing it in the API response. */
public interface VerificationCodeSender {

    /** Fails before account lookup if delivery is not configured. */
    default void checkAvailability() {
    }

    void send(VerificationChannel channel, String destination, String code);
}
