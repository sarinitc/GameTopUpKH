package org.example.gametopupkh.player.integration.volsever;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Non-secret Volsever settings. The API key is intentionally not bindable here;
 * it is supplied by the backend environment and never stored in source.
 */
@Validated
@ConfigurationProperties("player.verification.volsever")
public class VolseverProperties {

    private boolean enabled;

    @NotBlank
    private String baseUrl = "https://gate.volsever.com";

    @NotBlank
    private String freeFirePath = "/proxy/api/game/free-fire-global";

    @NotBlank
    private String mobileLegendsPath = "/proxy/api/game/mobile-legends";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(4);

    @AssertTrue(message = "Volsever base URL must be an absolute https URL without user info, query or fragment")
    public boolean isBaseUrlValid() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true; // @NotBlank reports the missing value
        }
        try {
            URI uri = URI.create(baseUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @AssertTrue(message = "Volsever paths must start with / and contain no query or fragment")
    public boolean isPathsValid() {
        return validPath(freeFirePath) && validPath(mobileLegendsPath);
    }

    private boolean validPath(String path) {
        return path == null || path.isBlank()
                || (path.startsWith("/") && !path.contains("?") && !path.contains("#"));
    }

    @AssertTrue(message = "Volsever connect timeout must be between 1 millisecond and 10 seconds")
    public boolean isConnectTimeoutValid() {
        return validTimeout(connectTimeout);
    }

    @AssertTrue(message = "Volsever read timeout must be between 1 millisecond and 10 seconds")
    public boolean isReadTimeoutValid() {
        return validTimeout(readTimeout);
    }

    private boolean validTimeout(Duration timeout) {
        return timeout != null
                && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(10)) <= 0;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getFreeFirePath() {
        return freeFirePath;
    }

    public void setFreeFirePath(String freeFirePath) {
        this.freeFirePath = freeFirePath;
    }

    public String getMobileLegendsPath() {
        return mobileLegendsPath;
    }

    public void setMobileLegendsPath(String mobileLegendsPath) {
        this.mobileLegendsPath = mobileLegendsPath;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
