package org.example.gametopupkh.auth.passwordreset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Local-development delivery. Email content is written under {@code target/dev-mail/}
 * so the reset link can be opened without an SMTP server. Only the file path and the
 * recipient are logged; the reset secret is never written to the log.
 */
@Service
@Profile("dev")
public class DevPasswordResetEmailSender implements PasswordResetEmailSender {

    private static final Logger log = LoggerFactory.getLogger(DevPasswordResetEmailSender.class);
    private static final Path DIRECTORY = Path.of("target", "dev-mail");

    @Override
    public void sendReset(String destination, String resetToken, String resetLink, Instant expiresAt) {
        String body = """
                Subject: GameTopUp KH - Reset Your Password

                Hello,

                We received a request to reset the GameTopUp KH account password for %s.

                Use this link to choose a new password:
                %s

                This request expires at %s.

                If you did not request a password reset, you can ignore this email.

                For security, never share your reset link or verification code.

                GameTopUp KH Support Team
                """.formatted(destination, resetLink, expiresAt);
        write("reset", destination, body);
    }

    @Override
    public void sendPasswordChanged(String destination) {
        String body = """
                Subject: GameTopUp KH - Your Password Was Changed

                Hello,

                The password for the GameTopUp KH account %s was changed successfully.

                If you did not make this change, contact GameTopUp KH support immediately.

                GameTopUp KH Support Team
                """.formatted(destination);
        write("changed", destination, body);
    }

    private void write(String kind, String destination, String body) {
        try {
            Files.createDirectories(DIRECTORY);
            Path file = DIRECTORY.resolve("password-" + kind + "-" + UUID.randomUUID() + ".txt");
            Files.writeString(file, body, StandardCharsets.UTF_8);
            log.info("DEV ONLY password {} email written to {}", kind, file.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("The development password reset email could not be written", e);
        }
    }
}
