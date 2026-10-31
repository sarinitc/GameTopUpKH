package org.example.gametopupkh.config;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origin allowlist for the public API.
 *
 * <p>Configured through {@code APP_CORS_ALLOWED_ORIGINS} (or
 * {@code app.cors.allowed-origins}) as a comma-separated list of exact origins.
 * An empty value disables cross-origin browser access entirely. A wildcard is
 * rejected at startup because it would let any site call customer endpoints with
 * a stolen bearer token.</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** Scheme plus host, without a path or trailing slash, for example https://shop.vercel.app. */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[^\\s/?]+$");

    private String allowedOrigins = "";

    /**
     * Rejects wildcards and malformed entries so a bad deployment fails at startup
     * instead of silently allowing the wrong browser origin.
     */
    @AssertTrue(message = "app.cors.allowed-origins must be a comma-separated list of absolute http(s) "
            + "origins without paths, query strings or wildcards")
    public boolean isAllowedOriginsValid() {
        return origins().stream().allMatch(origin -> ORIGIN.matcher(origin).matches());
    }

    /** Parsed, trimmed, non-empty origins. Duplicates are harmless for exact matching. */
    public List<String> origins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? "" : allowedOrigins;
    }
}
