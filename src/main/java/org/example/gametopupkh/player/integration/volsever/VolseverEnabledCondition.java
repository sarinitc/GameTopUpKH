package org.example.gametopupkh.player.integration.volsever;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Enables Volsever only when explicitly requested and the development mock is
 * not active. This keeps exactly one {@code PlayerLookupClient} registered.
 */
final class VolseverEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        boolean enabled = environment.getProperty("player.verification.volsever.enabled",
                Boolean.class, false);
        boolean mockEnabled = environment.getProperty("player.verification.mock-enabled",
                Boolean.class, false);
        return enabled && !mockEnabled;
    }
}
