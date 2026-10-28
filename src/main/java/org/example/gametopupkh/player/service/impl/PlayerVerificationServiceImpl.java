package org.example.gametopupkh.player.service.impl;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.player.PlayerAccountValidator;
import org.example.gametopupkh.player.config.PlayerVerificationProperties;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.example.gametopupkh.player.integration.PlayerLookupClient;
import org.example.gametopupkh.player.service.PlayerVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlayerVerificationServiceImpl implements PlayerVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PlayerVerificationServiceImpl.class);

    private final GameRepository games;
    private final GamePackageRepository packages;
    private final PlayerLookupClient client;
    private final PlayerVerificationProperties properties;
    private final Semaphore concurrencySemaphore;
    private final ExecutorService executor;

    public PlayerVerificationServiceImpl(GameRepository games,
                                        GamePackageRepository packages,
                                        PlayerLookupClient client,
                                        PlayerVerificationProperties properties) {
        this.games = games;
        this.packages = packages;
        this.client = client;
        this.properties = properties;
        int maxConcurrent = Math.max(1, properties.getMaxConcurrentLookups());
        this.concurrencySemaphore = new Semaphore(maxConcurrent);
        this.executor = Executors.newFixedThreadPool(maxConcurrent, runnable -> {
            Thread thread = new Thread(runnable, "player-lookup-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public PlayerVerifyResponse verify(String gameSlug, PlayerVerifyRequest request) {
        if (!PlayerAccountValidator.supportsGame(gameSlug)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYER_GAME_UNSUPPORTED",
                    "The game " + gameSlug + " is not supported for player verification");
        }

        Game game = games.findBySlug(gameSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND",
                        "Game not found with slug: " + gameSlug));
        if (!game.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_INACTIVE",
                    "Game " + game.getName() + " is currently inactive");
        }

        PlayerVerifyRequest normalized = PlayerAccountValidator.normalizeAndValidate(gameSlug, request);

        if (!client.supports(gameSlug)) {
            return unavailable(normalized.playerId(), normalized.zoneId());
        }

        return executeLookup(gameSlug, normalized);
    }

    @Override
    public Optional<PlayerVerifyResponse> revalidateForFulfillment(UUID packageId, String playerId, String zoneId) {
        if (packageId == null) {
            return Optional.of(unavailable(playerId, zoneId));
        }

        GamePackage pack = packages.findById(packageId).orElse(null);
        if (pack == null) {
            return Optional.of(unavailable(playerId, zoneId));
        }

        Game game = games.findById(pack.getGameId()).orElse(null);
        if (game == null || !game.isActive()) {
            return Optional.of(unavailable(playerId, zoneId));
        }

        String gameSlug = game.getSlug();
        if (!PlayerAccountValidator.supportsGame(gameSlug) || !client.supports(gameSlug)) {
            return Optional.empty();
        }

        PlayerVerifyRequest request = new PlayerVerifyRequest(playerId, zoneId);
        return Optional.of(executeLookup(gameSlug, request));
    }

    private PlayerVerifyResponse executeLookup(String gameSlug, PlayerVerifyRequest request) {
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(2);
        }

        boolean acquired;
        try {
            acquired = concurrencySemaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return unavailable(request.playerId(), request.zoneId());
        }

        if (!acquired) {
            return unavailable(request.playerId(), request.zoneId());
        }

        Future<PlayerVerifyResponse> future;
        try {
            future = executor.submit(() -> {
                try {
                    return client.lookup(gameSlug, request);
                } finally {
                    concurrencySemaphore.release();
                }
            });
        } catch (Exception ex) {
            concurrencySemaphore.release();
            return unavailable(request.playerId(), request.zoneId());
        }

        try {
            PlayerVerifyResponse raw = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return sanitizeResponse(request, raw);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return unavailable(request.playerId(), request.zoneId());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return unavailable(request.playerId(), request.zoneId());
        } catch (ExecutionException ex) {
            log.warn("Player lookup provider failed for game {}: provider threw an exception", gameSlug);
            return unavailable(request.playerId(), request.zoneId());
        } catch (Exception ex) {
            log.warn("Player lookup failed for game {}", gameSlug);
            return unavailable(request.playerId(), request.zoneId());
        }
    }

    private PlayerVerifyResponse sanitizeResponse(PlayerVerifyRequest request, PlayerVerifyResponse response) {
        if (response == null || response.status() == null) {
            return unavailable(request.playerId(), request.zoneId());
        }
        if (!Objects.equals(request.playerId(), response.playerId())) {
            return unavailable(request.playerId(), request.zoneId());
        }
        if (!Objects.equals(request.zoneId(), response.zoneId())) {
            return unavailable(request.playerId(), request.zoneId());
        }

        String provider = (response.provider() == null || response.provider().isBlank())
                ? client.provider() : response.provider();

        if (response.status() == PlayerVerificationStatus.VERIFIED) {
            String nickname = response.nickname();
            if (nickname == null || nickname.isBlank() || nickname.length() > 200) {
                return unavailable(request.playerId(), request.zoneId());
            }
            if (nickname.contains("\n") || nickname.contains("\r")) {
                return unavailable(request.playerId(), request.zoneId());
            }
            String region = response.region();
            if (region != null && region.length() > 100) {
                region = region.substring(0, 100);
            }
            return new PlayerVerifyResponse(PlayerVerificationStatus.VERIFIED, request.playerId(),
                    request.zoneId(), nickname.trim(), region != null ? region.trim() : null, provider);
        }

        if (response.status() == PlayerVerificationStatus.INVALID) {
            return new PlayerVerifyResponse(PlayerVerificationStatus.INVALID, request.playerId(),
                    request.zoneId(), null, null, provider);
        }

        return unavailable(request.playerId(), request.zoneId());
    }

    private PlayerVerifyResponse unavailable(String playerId, String zoneId) {
        return new PlayerVerifyResponse(PlayerVerificationStatus.UNAVAILABLE, playerId, zoneId,
                null, null, client.provider());
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
