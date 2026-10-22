package org.example.gametopupkh.auth.verification;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactVerificationService {

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final VerificationCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeSender codeSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public ContactVerificationService(UserRepository userRepository, VerificationCodeRepository codeRepository,
            PasswordEncoder passwordEncoder, VerificationCodeSender codeSender) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.passwordEncoder = passwordEncoder;
        this.codeSender = codeSender;
    }

    @Transactional
    public void requestCode(VerificationChannel channel, String destination) {
        codeSender.checkAvailability();
        String normalized = normalize(channel, destination);
        Optional<User> found = findAndLockUser(channel, normalized);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        if (!canVerify(user) || isVerified(user, channel)) {
            return;
        }
        Instant now = Instant.now();
        VerificationCode challenge = codeRepository.findByUserIdAndChannel(user.getId(), channel)
                .orElseGet(VerificationCode::new);
        if (challenge.getSentAt() != null && now.isBefore(challenge.getSentAt().plus(RESEND_COOLDOWN))) {
            return;
        }

        String code = generateCode(challenge.getCodeHash());
        challenge.setUser(user);
        challenge.setChannel(channel);
        challenge.setDestination(normalized);
        challenge.setCodeHash(passwordEncoder.encode(code));
        challenge.setSentAt(now);
        challenge.setExpiresAt(now.plus(CODE_LIFETIME));
        challenge.setFailedAttempts(0);
        challenge.setConsumedAt(null);
        codeRepository.saveAndFlush(challenge);
        try {
            codeSender.send(channel, normalized, code);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_DELIVERY_FAILED",
                    "The verification code could not be delivered. Please try again later");
        }
    }

    // Failed guesses must commit their attempt counter even though the API returns an error.
    @Transactional(noRollbackFor = ApiException.class)
    public VerificationResponse verifyCode(VerificationChannel channel, String destination, String code) {
        String normalized = normalize(channel, destination);
        User user = findAndLockUser(channel, normalized).orElseThrow(this::invalidCode);
        if (!canVerify(user)) {
            throw invalidCode();
        }
        VerificationCode challenge = codeRepository.findByUserIdAndChannel(user.getId(), channel)
                .orElseThrow(this::invalidCode);
        Instant now = Instant.now();
        if (!normalized.equals(challenge.getDestination())
                || challenge.getConsumedAt() != null
                || !now.isBefore(challenge.getExpiresAt())
                || challenge.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
            throw invalidCode();
        }
        if (!passwordEncoder.matches(code, challenge.getCodeHash())) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            codeRepository.save(challenge);
            throw invalidCode();
        }

        if (channel == VerificationChannel.EMAIL) {
            user.setEmailVerified(true);
        } else {
            user.setPhoneVerified(true);
        }
        challenge.setConsumedAt(now);
        codeRepository.save(challenge);
        userRepository.save(user);
        return new VerificationResponse(user.isEmailVerified(), user.isPhoneVerified());
    }

    private Optional<User> findAndLockUser(VerificationChannel channel, String destination) {
        return channel == VerificationChannel.EMAIL
                ? userRepository.findForVerificationByEmail(destination)
                : userRepository.findForVerificationByPhoneNumber(destination);
    }

    private boolean canVerify(User user) {
        return user.getAccountStatus() == AccountStatus.ACTIVE
                || user.getAccountStatus() == AccountStatus.PENDING_VERIFICATION;
    }

    private boolean isVerified(User user, VerificationChannel channel) {
        return channel == VerificationChannel.EMAIL ? user.isEmailVerified() : user.isPhoneVerified();
    }

    private String normalize(VerificationChannel channel, String destination) {
        String trimmed = destination.trim();
        return channel == VerificationChannel.EMAIL ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private String generateCode(String previousHash) {
        String candidate;
        do {
            candidate = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        } while (previousHash != null && passwordEncoder.matches(candidate, previousHash));
        return candidate;
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE",
                "The verification code is invalid, expired or already used. Request a new code if needed");
    }
}
