package org.example.gametopupkh.auth.verification;

import org.example.gametopupkh.common.exception.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Fails closed until a real email/SMS delivery implementation is configured. */
@Service
@Profile("!dev")
public class UnavailableVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void checkAvailability() {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_DELIVERY_UNAVAILABLE",
                "Verification delivery is not configured");
    }

    @Override
    public void send(VerificationChannel channel, String destination, String code) {
        checkAvailability();
    }
}
