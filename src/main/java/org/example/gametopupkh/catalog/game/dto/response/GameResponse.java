package org.example.gametopupkh.catalog.game.dto.response;

import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.catalog.game.entity.Game;

public record GameResponse(
        UUID id,
        String name,
        String slug,
        String playerIdLabel,
        boolean requiresZoneId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static GameResponse from(Game game) {
        return new GameResponse(
                game.getId(),
                game.getName(),
                game.getSlug(),
                game.getPlayerIdLabel(),
                game.isRequiresZoneId(),
                game.isActive(),
                game.getCreatedAt(),
                game.getUpdatedAt());
    }
}
