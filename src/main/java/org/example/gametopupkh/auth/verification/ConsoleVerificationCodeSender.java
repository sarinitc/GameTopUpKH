package org.example.gametopupkh.auth.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Local development delivery; this sender does not send email or SMS. */
@Service
@Profile("dev")
public class ConsoleVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleVerificationCodeSender.class);

    @Override
    public void send(VerificationChannel channel, String destination, String code) {
        log.info("DEV ONLY verification delivery: channel={}, destination={}, code={}", channel, destination, code);
    }
}
