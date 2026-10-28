package org.example.gametopupkh.auth.passwordreset;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.example.gametopupkh.audit.dto.AuditLogRecord;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.service.AuditLogService;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Coordinates rate limiting, the short database transactions and the external email
 * delivery. The email call is intentionally outside any transaction, and delivery or
 * audit failures are swallowed so they cannot reveal whether an account exists.
 */
@Service
public class PasswordResetServiceImpl implements PasswordResetService {

    public static final String FORGOT_PASSWORD_MESSAGE =
            "If an account exists for this email, password reset instructions have been sent.";
    public static final String PASSWORD_RESET_MESSAGE =
            "Password has been reset successfully. Please log in again.";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenStore tokenStore;
    private final PasswordResetEmailSender emailSender;
    private final PasswordResetRateLimiter rateLimiter;
    private final PasswordResetProperties properties;
    private final AuditLogService auditLogService;

    public PasswordResetServiceImpl(UserRepository userRepository, PasswordResetTokenStore tokenStore,
            PasswordResetEmailSender emailSender, PasswordResetRateLimiter rateLimiter,
            PasswordResetProperties properties, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.tokenStore = tokenStore;
        this.emailSender = emailSender;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    @Override
    public void requestReset(ForgotPasswordRequest request, String ipAddress) {
        String email = normalizeEmail(request.email());
        enforceLimit(rateLimiter.tryRequestForEmail(email));
        enforceLimit(rateLimiter.tryRequestForIp(ipAddress));

        if (!deliveryAvailable()) {
            return;
        }

        Optional<User> found = userRepository.findByEmailIgnoreCase(email);
        if (found.isEmpty()) {
            return;
        }

        PasswordResetTokenStore.IssuedResetToken issued = tokenStore.issue(found.get().getId(), ipAddress);
        if (issued == null) {
            return;
        }

        try {
            String resetLink = resetLink(issued.token());
            emailSender.sendReset(issued.destination(), issued.token(), resetLink, issued.expiresAt());
        } catch (RuntimeException e) {
            // Do not include the token, link or transport payload in the log.
            log.warn("Password reset email delivery failed for userId={} ({})",
                    found.get().getId(), e.getClass().getSimpleName());
        }
    }

    @Override
    public void resetPassword(ResetPasswordRequest request, String ipAddress) {
        enforceLimit(rateLimiter.tryResetForIp(ipAddress));

        PasswordResetTokenStore.PasswordResetResult result =
                tokenStore.consume(request.token(), request.newPassword(), ipAddress);

        recordAudit(result);
        try {
            emailSender.sendPasswordChanged(result.email());
        } catch (RuntimeException e) {
            log.warn("Password change notification failed for userId={} ({})",
                    result.userId(), e.getClass().getSimpleName());
        }
    }

    private void recordAudit(PasswordResetTokenStore.PasswordResetResult result) {
        try {
            auditLogService.record(new AuditLogRecord(AuditAction.PASSWORD_RESET, result.userId(),
                    result.email(), null, "users", result.userId().toString(),
                    "Password reset completed", null, result.ipAddress()));
        } catch (RuntimeException e) {
            log.warn("Password reset audit could not be recorded for userId={} ({})",
                    result.userId(), e.getClass().getSimpleName());
        }
    }

    private boolean deliveryAvailable() {
        try {
            emailSender.checkAvailability();
            return true;
        } catch (RuntimeException e) {
            log.error("Password reset email delivery is not configured; returning the generic response");
            return false;
        }
    }

    private String resetLink(String token) {
        String base = properties.getFrontendBaseUrl().trim().replaceAll("/+$", "");
        String path = properties.getResetPath().trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void enforceLimit(long retryAfterSeconds) {
        if (retryAfterSeconds > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_RESET_RATE_LIMITED",
                    "Too many password reset requests. Please try again in "
                            + retryAfterSeconds + " seconds.");
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
