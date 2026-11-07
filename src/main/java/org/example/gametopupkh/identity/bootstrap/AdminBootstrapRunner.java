package org.example.gametopupkh.identity.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the one-time ADMIN bootstrap at startup only when it is explicitly
 * enabled. The runner never starts a web endpoint and never aborts the service
 * when the bootstrap is misconfigured; the operator must inspect the startup log.
 */
@Component
@ConditionalOnProperty(prefix = "app.admin-bootstrap", name = "enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapService bootstrapService;

    public AdminBootstrapRunner(AdminBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            AdminBootstrapOutcome outcome = bootstrapService.provision();
            log.info("One-time ADMIN bootstrap completed: {}", outcome);
        } catch (RuntimeException ex) {
            log.error("One-time ADMIN bootstrap failed and no role was granted: {}", ex.getMessage());
            log.debug("One-time ADMIN bootstrap failure details", ex);
        }
    }
}
