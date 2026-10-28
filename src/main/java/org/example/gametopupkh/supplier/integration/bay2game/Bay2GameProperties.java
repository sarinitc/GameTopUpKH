package org.example.gametopupkh.supplier.integration.bay2game;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Non-secret settings only. The API key is intentionally not a bindable property. */
@Validated
@ConfigurationProperties("supplier.bay2game")
public class Bay2GameProperties {
    private boolean enabled;
    private boolean providerConfirmed;
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(10);

    @AssertTrue(message = "Bay2Game requires provider confirmation before enablement")
    public boolean isProviderConfirmationValid() { return !enabled || providerConfirmed; }

    @AssertTrue(message = "connect timeout must be between 1 millisecond and 10 seconds")
    public boolean isConnectTimeoutValid() { return validTimeout(connectTimeout, 10); }

    @AssertTrue(message = "read timeout must be between 1 millisecond and 30 seconds")
    public boolean isReadTimeoutValid() { return validTimeout(readTimeout, 30); }

    private boolean validTimeout(Duration timeout, int maxSeconds) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(maxSeconds)) <= 0;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isProviderConfirmed() { return providerConfirmed; }
    public void setProviderConfirmed(boolean providerConfirmed) { this.providerConfirmed = providerConfirmed; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
