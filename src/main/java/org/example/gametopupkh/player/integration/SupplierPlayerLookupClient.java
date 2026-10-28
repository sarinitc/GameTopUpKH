package org.example.gametopupkh.player.integration;

import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Safe fallback until an authorized supplier's account lookup contract is configured.
 * No endpoint, authentication scheme, or account information is guessed here.
 */
@Component
@ConditionalOnProperty(name = "player.verification.mock-enabled", havingValue = "false", matchIfMissing = true)
public class SupplierPlayerLookupClient implements PlayerLookupClient {

    @Override
    public boolean supports(String gameSlug) {
        return false;
    }

    @Override
    public String provider() {
        return "SUPPLIER_API";
    }

    @Override
    public PlayerVerifyResponse lookup(String gameSlug, PlayerVerifyRequest request) {
        return new PlayerVerifyResponse(PlayerVerificationStatus.UNAVAILABLE, request.playerId(), request.zoneId(),
                null, null, provider());
    }
}
