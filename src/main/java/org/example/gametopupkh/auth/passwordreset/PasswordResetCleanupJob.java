package org.example.gametopupkh.auth.passwordreset;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired and long-used reset credentials; the raw token is never recoverable. */
@Component
@ConditionalOnProperty(name = "app.password-reset.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetCleanupJob.class);

    private final PasswordResetTokenStore tokenStore;
    private final PasswordResetProperties properties;

    public PasswordResetCleanupJob(PasswordResetTokenStore tokenStore, PasswordResetProperties properties) {
        this.tokenStore = tokenStore;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.password-reset.cleanup-interval:PT1H}")
    public void removeStaleTokens() {
        Instant cutoff = Instant.now().minus(properties.getCleanupRetention());
        int removed = tokenStore.deleteStale(cutoff);
        if (removed > 0) {
            log.info("Removed {} stale password reset token records", removed);
        }
    }
}
