package org.example.gametopupkh.player.service;

import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;

public interface PlayerVerificationService extends AutoCloseable {

    PlayerVerifyResponse verify(String gameSlug, PlayerVerifyRequest request);

    Optional<PlayerVerifyResponse> revalidateForFulfillment(UUID packageId, String playerId, String zoneId);

    @Override
    default void close() {
    }
}
