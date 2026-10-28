package org.example.gametopupkh.auth.passwordreset;

import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.auth.RefreshTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional database portion of the workflow. Email delivery and audit writes are
 * deliberately outside these transactions; only the short database work is transactional.
 */
@Component
public class PasswordResetTokenStore {

    private final PasswordResetTokenRepository resetTokens;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenCodec codec;
    private final PasswordResetProperties properties;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetTokenStore(PasswordResetTokenRepository resetTokens, UserRepository users,
            RefreshTokenRepository refreshTokens, PasswordResetTokenCodec codec,
            PasswordResetProperties properties, PasswordEncoder passwordEncoder) {
        this.resetTokens = resetTokens;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.codec = codec;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Locks the user row, retires older credentials, stores only the hash and returns the
     * raw token for delivery. An empty result means the account is gone/inactive or the
     * resend cooldown is still active; the caller still returns the generic response.
     */
    @Transactional
    public IssuedResetToken issue(UUID userId, String ipAddress) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getAccountStatus() != AccountStatus.ACTIVE) {
            return null;
        }

        Instant now = Instant.now();
        var latest = resetTokens.findTopByUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(userId);
        if (latest.isPresent()
                && latest.get().getCreatedAt().plus(properties.getResendCooldown()).isAfter(now)) {
            return null;
        }

        resetTokens.invalidateActiveForUser(userId, now);
        // The bulk update detaches managed entities, so use a fresh reference and captured values.
        String email = user.getEmail();
        String rawToken = codec.generate();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(users.getReferenceById(userId));
        token.setTokenHash(codec.hash(rawToken));
        token.setRequestedIp(truncate(ipAddress, 45));
        token.setExpiresAt(now.plus(properties.getTokenExpiration()));
        resetTokens.save(token);
        return new IssuedResetToken(rawToken, email, token.getExpiresAt());
    }

    /**
     * Consumes the token under a row lock, re-checks its state, updates the password hash,
     * increments the token version and revokes every outstanding refresh token in the same
     * committed transaction.
     */
    @Transactional
    public PasswordResetResult consume(String rawToken, String newPassword, String ipAddress) {
        String tokenHash = codec.hash(rawToken);
        // Lock order is user then token, matching issue(), to avoid a deadlock between
        // a concurrent reset and a concurrent new reset request for the same account.
        UUID tokenUserId = resetTokens.findUserIdByTokenHash(tokenHash)
                .orElseThrow(PasswordResetTokenStore::invalidToken);
        User user = users.findByIdForUpdate(tokenUserId)
                .orElseThrow(PasswordResetTokenStore::invalidToken);
        PasswordResetToken token = resetTokens.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(PasswordResetTokenStore::invalidToken);
        Instant now = Instant.now();
        if (token.isUsed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_ALREADY_USED",
                    "This password reset link has already been used");
        }
        if (token.isExpired(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_EXPIRED",
                    "This password reset link has expired");
        }

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                    "The account is " + user.getAccountStatus().name().toLowerCase());
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);

        UUID userId = user.getId();
        String email = user.getEmail();
        String fullName = user.getFullName();

        token.setUsedAt(now);
        resetTokens.save(token);
        resetTokens.invalidateActiveForUser(userId, now);
        // Bulk update clears the persistence context, so the snapshot above is used below.
        refreshTokens.revokeAllForUser(userId, now);

        return new PasswordResetResult(userId, email, fullName, truncate(ipAddress, 45));
    }

    @Transactional
    public int deleteStale(Instant cutoff) {
        return resetTokens.deleteStale(cutoff);
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN",
                "The password reset token is invalid");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public record IssuedResetToken(String token, String destination, Instant expiresAt) {
    }

    public record PasswordResetResult(UUID userId, String email, String fullName, String ipAddress) {
    }
}
