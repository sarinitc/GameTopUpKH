package org.example.gametopupkh.catalog.packagecatalog.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;

public record GamePackageResponse(
        UUID id,
        UUID gameId,
        String name,
        String sku,
        Integer diamondAmount,
        BigDecimal price,
        String currency,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static GamePackageResponse from(GamePackage gamePackage) {
        return new GamePackageResponse(
                gamePackage.getId(),
                gamePackage.getGameId(),
                gamePackage.getName(),
                gamePackage.getSku(),
                gamePackage.getDiamondAmount(),
                gamePackage.getPrice(),
                gamePackage.getCurrency(),
                gamePackage.isActive(),
                gamePackage.getCreatedAt(),
                gamePackage.getUpdatedAt());
    }
}
