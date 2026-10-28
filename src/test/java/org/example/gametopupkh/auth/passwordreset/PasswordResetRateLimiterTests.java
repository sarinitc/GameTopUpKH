package org.example.gametopupkh.auth.passwordreset;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetRateLimiterTests {

    private PasswordResetProperties properties() {
        PasswordResetProperties properties = new PasswordResetProperties();
        properties.setRateLimitWindow(Duration.ofMinutes(15));
        properties.setRequestsPerEmailPerWindow(2);
        properties.setRequestsPerIpPerWindow(3);
        properties.setResetAttemptsPerIpPerWindow(1);
        return properties;
    }

    @Test
    void emailFloodingIsBoundedPerDestination() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(properties());
        assertThat(limiter.tryRequestForEmail("known@example.com")).isZero();
        assertThat(limiter.tryRequestForEmail("known@example.com")).isZero();
        assertThat(limiter.tryRequestForEmail("known@example.com")).isPositive();
        assertThat(limiter.tryRequestForEmail("other@example.com")).isZero();
    }

    @Test
    void ipAbuseIsBoundedIndependentlyOfAccountExistence() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(properties());
        assertThat(limiter.tryRequestForIp("192.0.2.10")).isZero();
        assertThat(limiter.tryRequestForIp("192.0.2.10")).isZero();
        assertThat(limiter.tryRequestForIp("192.0.2.10")).isZero();
        assertThat(limiter.tryRequestForIp("192.0.2.10")).isPositive();
        assertThat(limiter.tryRequestForIp("192.0.2.11")).isZero();
    }

    @Test
    void resetAttemptsAreBoundedBySourceAddress() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(properties());
        assertThat(limiter.tryResetForIp("198.51.100.7")).isZero();
        assertThat(limiter.tryResetForIp("198.51.100.7")).isPositive();
        assertThat(limiter.tryResetForIp("198.51.100.8")).isZero();
    }

    @Test
    void generatedSecretIsOpaqueUnpredictableAndOnlyItsHashIsStable() {
        PasswordResetTokenCodec codec = new PasswordResetTokenCodec();
        String first = codec.generate();
        String second = codec.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSize(43);
        assertThat(first).matches("[A-Za-z0-9_-]+");
        assertThat(codec.hash(first)).hasSize(64).matches("[0-9a-f]+");
        assertThat(codec.hash(first)).isEqualTo(codec.hash(first));
        assertThat(codec.hash(first)).isNotEqualTo(codec.hash(second));
        assertThat(codec.hash(first)).doesNotContain(first);
    }
}
