package org.example.gametopupkh.player.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Reports which player lookup provider is active when the application starts.
 *
 * <p>Production previously returned {@code UNAVAILABLE} with
 * {@code provider=SUPPLIER_API} because the Volsever adapter was not selected.
 * That fallback is safe but silent, so this component makes the active provider
 * and the relevant switches visible in the startup log. It never logs the API
 * key, request URLs or player identifiers.</p>
 */
@Component
public class PlayerLookupClientDiagnostics implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PlayerLookupClientDiagnostics.class);

    private final PlayerLookupClient client;
    private final Environment environment;

    public PlayerLookupClientDiagnostics(PlayerLookupClient client, Environment environment) {
        this.client = client;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean freeFire = client.supports("free-fire");
        boolean mobileLegends = client.supports("mobile-legends");
        boolean volseverEnabled = environment.getProperty(
                "player.verification.volsever.enabled", Boolean.class, false);
        boolean apiKeyPresent = StringUtils.hasText(environment.getProperty("VOLSEVER_API_KEY"));

        log.info("Player verification provider active: {} (free-fire={}, mobile-legends={}, "
                        + "volsever.enabled={}, volsever.apiKeyPresent={})",
                client.provider(), freeFire, mobileLegends, volseverEnabled, apiKeyPresent);

        if (!freeFire && !mobileLegends) {
            log.warn("No live player verification provider is active; lookups will return UNAVAILABLE. "
                    + "In production set VOLSEVER_ENABLED=true and VOLSEVER_API_KEY in the server "
                    + "environment, then redeploy.");
        }
    }
}
