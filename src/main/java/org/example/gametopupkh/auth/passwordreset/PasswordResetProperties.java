package org.example.gametopupkh.auth.passwordreset;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the forgot/reset password workflow. Destructive defaults are
 * deliberately conservative: a ten-minute single-use token and a short resend cooldown.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.password-reset")
public class PasswordResetProperties {

    @NotNull
    private Duration tokenExpiration = Duration.ofMinutes(10);

    @NotNull
    private Duration resendCooldown = Duration.ofSeconds(60);

    @NotBlank
    private String frontendBaseUrl = "http://localhost:3000";

    @NotBlank
    private String resetPath = "/reset-password";

    @NotNull
    private Duration rateLimitWindow = Duration.ofMinutes(15);

    @Min(1)
    @Max(1000)
    private int requestsPerEmailPerWindow = 3;

    @Min(1)
    @Max(10000)
    private int requestsPerIpPerWindow = 10;

    @Min(1)
    @Max(10000)
    private int resetAttemptsPerIpPerWindow = 10;

    private boolean cleanupEnabled = true;

    @NotNull
    private Duration cleanupInterval = Duration.ofHours(1);

    @NotNull
    private Duration cleanupRetention = Duration.ofDays(7);

    public Duration getTokenExpiration() {
        return tokenExpiration;
    }

    public void setTokenExpiration(Duration tokenExpiration) {
        this.tokenExpiration = tokenExpiration;
    }

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public String getResetPath() {
        return resetPath;
    }

    public void setResetPath(String resetPath) {
        this.resetPath = resetPath;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public int getRequestsPerEmailPerWindow() {
        return requestsPerEmailPerWindow;
    }

    public void setRequestsPerEmailPerWindow(int requestsPerEmailPerWindow) {
        this.requestsPerEmailPerWindow = requestsPerEmailPerWindow;
    }

    public int getRequestsPerIpPerWindow() {
        return requestsPerIpPerWindow;
    }

    public void setRequestsPerIpPerWindow(int requestsPerIpPerWindow) {
        this.requestsPerIpPerWindow = requestsPerIpPerWindow;
    }

    public int getResetAttemptsPerIpPerWindow() {
        return resetAttemptsPerIpPerWindow;
    }

    public void setResetAttemptsPerIpPerWindow(int resetAttemptsPerIpPerWindow) {
        this.resetAttemptsPerIpPerWindow = resetAttemptsPerIpPerWindow;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public Duration getCleanupRetention() {
        return cleanupRetention;
    }

    public void setCleanupRetention(Duration cleanupRetention) {
        this.cleanupRetention = cleanupRetention;
    }
}
