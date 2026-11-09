package org.example.gametopupkh.supplier.integration.vsc;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Non-secret VSC supplier settings. The API key is intentionally not a bindable
 * property; it is read directly from the process environment when the adapter is
 * enabled. {@code enabled} without {@code providerConfirmed} fails startup, so the
 * top-up call can never become active by configuration drift.
 */
@Validated
@ConfigurationProperties("supplier.vsc")
public class VscProperties {

    private boolean enabled;
    private boolean providerConfirmed;

    @NotBlank
    @Size(max = 100)
    private String merchantName = "GameTopUp-KH";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(10);

    @AssertTrue(message = "VSC requires provider confirmation before enablement")
    public boolean isProviderConfirmationValid() {
        return !enabled || providerConfirmed;
    }

    @AssertTrue(message = "VSC merchant name must not contain control characters")
    public boolean isMerchantNameValid() {
        return merchantName != null && merchantName.chars().noneMatch(Character::isISOControl);
    }

    @AssertTrue(message = "connect timeout must be between 1 millisecond and 10 seconds")
    public boolean isConnectTimeoutValid() {
        return validTimeout(connectTimeout, 10);
    }

    @AssertTrue(message = "read timeout must be between 1 millisecond and 30 seconds")
    public boolean isReadTimeoutValid() {
        return validTimeout(readTimeout, 30);
    }

    private boolean validTimeout(Duration timeout, int maxSeconds) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(maxSeconds)) <= 0;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isProviderConfirmed() { return providerConfirmed; }
    public void setProviderConfirmed(boolean providerConfirmed) { this.providerConfirmed = providerConfirmed; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
