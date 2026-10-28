package org.example.gametopupkh.player;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.springframework.http.HttpStatus;

public final class PlayerAccountValidator {

    private PlayerAccountValidator() {
    }

    public static boolean supportsGame(String gameSlug) {
        if (gameSlug == null) {
            return false;
        }
        String normalized = gameSlug.trim().toLowerCase();
        return "free-fire".equals(normalized) || "mobile-legends".equals(normalized);
    }

    public static PlayerVerifyRequest normalizeAndValidate(String gameSlug, PlayerVerifyRequest request) {
        if (!supportsGame(gameSlug)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYER_GAME_UNSUPPORTED",
                    "The game " + gameSlug + " is not supported for player verification");
        }
        String slug = gameSlug.trim().toLowerCase();

        if (request == null || request.playerId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLAYER_ID", "Player ID is required");
        }

        String playerId = request.playerId().trim();
        if (playerId.isEmpty() || !playerId.matches("^[0-9]{1,20}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLAYER_ID",
                    "Player ID must be between 1 and 20 numeric digits");
        }

        String zoneId = request.zoneId() == null ? null : request.zoneId().trim();
        if (zoneId != null && zoneId.isEmpty()) {
            zoneId = null;
        }

        if ("mobile-legends".equals(slug)) {
            if (zoneId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ZONE_ID_REQUIRED",
                        "Mobile Legends requires a zone id");
            }
            if (!zoneId.matches("^[0-9]{1,10}$")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ZONE_ID",
                        "Zone ID must be between 1 and 10 numeric digits");
            }
        } else if ("free-fire".equals(slug)) {
            if (zoneId != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNEXPECTED_ZONE_ID",
                        "Free Fire does not support zone id");
            }
        }

        return new PlayerVerifyRequest(playerId, zoneId);
    }
}
