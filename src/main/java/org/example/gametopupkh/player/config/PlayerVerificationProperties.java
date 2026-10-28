package org.example.gametopupkh.player.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "player.verification")
public class PlayerVerificationProperties {

    @NotNull
    private Duration timeout = Duration.ofSeconds(2);

    @Min(1)
    @Max(64)
    private int maxConcurrentLookups = 8;

    @Min(1)
    @Max(10000)
    private int requestsPerMinute = 10;

    @Min(1)
    @Max(100000)
    private int globalRequestsPerMinute = 300;

    @Min(1)
    @Max(100000)
    private int maxRateLimitKeys = 10000;

    private boolean mockEnabled;

    @Valid
    @NotNull
    @Size(max = 1000)
    private List<Fixture> mockFixtures = new ArrayList<>();

    @AssertTrue(message = "timeout must be positive and at most 10 seconds")
    public boolean isTimeoutWithinBounds() {
        return timeout == null || (!timeout.isZero() && !timeout.isNegative()
                && timeout.compareTo(Duration.ofSeconds(10)) <= 0);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxConcurrentLookups() {
        return maxConcurrentLookups;
    }

    public void setMaxConcurrentLookups(int maxConcurrentLookups) {
        this.maxConcurrentLookups = maxConcurrentLookups;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getGlobalRequestsPerMinute() {
        return globalRequestsPerMinute;
    }

    public void setGlobalRequestsPerMinute(int globalRequestsPerMinute) {
        this.globalRequestsPerMinute = globalRequestsPerMinute;
    }

    public int getMaxRateLimitKeys() {
        return maxRateLimitKeys;
    }

    public void setMaxRateLimitKeys(int maxRateLimitKeys) {
        this.maxRateLimitKeys = maxRateLimitKeys;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public void setMockEnabled(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    public List<Fixture> getMockFixtures() {
        return mockFixtures;
    }

    public void setMockFixtures(List<Fixture> mockFixtures) {
        this.mockFixtures = mockFixtures;
    }

    public static class Fixture {

        @NotBlank
        @Pattern(regexp = "^(free-fire|mobile-legends)$")
        private String gameSlug;

        @NotBlank
        @Pattern(regexp = "^[0-9]{1,20}$")
        private String playerId;

        @Pattern(regexp = "^[0-9]{1,10}$")
        private String zoneId;

        @Size(max = 100)
        private String nickname;

        @Size(max = 100)
        private String region;

        @NotNull
        private PlayerVerificationStatus status;

        @AssertTrue(message = "fixture status must be VERIFIED or INVALID; verified fixtures need a nickname")
        public boolean isResultValid() {
            return status == PlayerVerificationStatus.INVALID
                    || (status == PlayerVerificationStatus.VERIFIED && nickname != null && !nickname.isBlank());
        }

        @AssertTrue(message = "Mobile Legends fixtures require a zone; Free Fire fixtures must omit the zone")
        public boolean isZoneValid() {
            return "mobile-legends".equals(gameSlug) ? zoneId != null && !zoneId.isBlank() : zoneId == null;
        }

        public String getGameSlug() {
            return gameSlug;
        }

        public void setGameSlug(String gameSlug) {
            this.gameSlug = gameSlug;
        }

        public String getPlayerId() {
            return playerId;
        }

        public void setPlayerId(String playerId) {
            this.playerId = playerId;
        }

        public String getZoneId() {
            return zoneId;
        }

        public void setZoneId(String zoneId) {
            this.zoneId = zoneId;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public PlayerVerificationStatus getStatus() {
            return status;
        }

        public void setStatus(PlayerVerificationStatus status) {
            this.status = status;
        }
    }
}
