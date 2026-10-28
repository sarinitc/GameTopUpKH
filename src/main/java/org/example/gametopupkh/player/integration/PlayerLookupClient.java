package org.example.gametopupkh.player.integration;

import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;

/** Read-only account lookup. Implementations must not initiate payment or delivery. */
public interface PlayerLookupClient {

    boolean supports(String gameSlug);

    String provider();

    PlayerVerifyResponse lookup(String gameSlug, PlayerVerifyRequest request);
}
