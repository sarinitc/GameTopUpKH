package org.example.gametopupkh.player.dto;

public record PlayerVerifyResponse(
        PlayerVerificationStatus status,
        String playerId,
        String zoneId,
        String nickname,
        String region,
        String provider) {
}
