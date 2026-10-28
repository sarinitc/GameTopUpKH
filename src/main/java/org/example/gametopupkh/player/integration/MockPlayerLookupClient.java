package org.example.gametopupkh.player.integration;

import java.util.Objects;

import org.example.gametopupkh.player.PlayerAccountValidator;
import org.example.gametopupkh.player.config.PlayerVerificationProperties;
import org.example.gametopupkh.player.config.PlayerVerificationProperties.Fixture;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Isolated development fixtures. Unknown accounts never receive invented account details. */
@Component
@Profile("dev & !prod & !production")
@ConditionalOnProperty(name = "player.verification.mock-enabled", havingValue = "true")
public class MockPlayerLookupClient implements PlayerLookupClient {

    private final PlayerVerificationProperties properties;

    public MockPlayerLookupClient(PlayerVerificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String gameSlug) {
        return PlayerAccountValidator.supportsGame(gameSlug);
    }

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public PlayerVerifyResponse lookup(String gameSlug, PlayerVerifyRequest request) {
        if (supports(gameSlug)) {
            for (Fixture fixture : properties.getMockFixtures()) {
                if (Objects.equals(gameSlug, fixture.getGameSlug())
                        && Objects.equals(request.playerId(), fixture.getPlayerId())
                        && Objects.equals(request.zoneId(), fixture.getZoneId())) {
                    boolean verified = fixture.getStatus() == PlayerVerificationStatus.VERIFIED;
                    return new PlayerVerifyResponse(fixture.getStatus(), request.playerId(), request.zoneId(),
                            verified ? fixture.getNickname() : null, verified ? fixture.getRegion() : null, provider());
                }
            }
        }
        return new PlayerVerifyResponse(PlayerVerificationStatus.UNAVAILABLE, request.playerId(), request.zoneId(),
                null, null, provider());
    }
}
