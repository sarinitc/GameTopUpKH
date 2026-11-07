package org.example.gametopupkh.identity.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the one-time, server-side first-ADMIN bootstrap.
 *
 * <p>The bootstrap is disabled by default. Enabling it requires an exact target
 * account email and the confirmation phrase {@code GRANT_ADMIN}; the target
 * account must already exist, be {@code ACTIVE} and have a verified email or
 * phone number. There is intentionally no HTTP endpoint for this operation.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.admin-bootstrap")
public class AdminBootstrapProperties {

    private boolean enabled;

    private String email = "";

    private String confirmation = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? "" : email;
    }

    public String getConfirmation() {
        return confirmation;
    }

    public void setConfirmation(String confirmation) {
        this.confirmation = confirmation == null ? "" : confirmation;
    }
}
