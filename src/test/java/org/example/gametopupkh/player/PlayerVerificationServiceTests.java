package org.example.gametopupkh.player;

import java.time.Duration;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.player.config.PlayerVerificationProperties;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.example.gametopupkh.player.integration.PlayerLookupClient;
import org.example.gametopupkh.player.service.impl.PlayerVerificationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerVerificationServiceTests {
    private final GameRepository games = mock(GameRepository.class);
    private final GamePackageRepository packages = mock(GamePackageRepository.class);
    private final PlayerLookupClient client = mock(PlayerLookupClient.class);
    private final PlayerVerificationProperties properties = new PlayerVerificationProperties();
    private final PlayerVerifyRequest request = new PlayerVerifyRequest("00555555", null);
    private PlayerVerificationServiceImpl service;

    @BeforeEach
    void setup() {
        Game game = new Game();
        game.setId(UUID.randomUUID());
        game.setSlug("free-fire");
        when(games.findBySlug("free-fire")).thenReturn(Optional.of(game));
        when(client.supports("free-fire")).thenReturn(true);
        when(client.provider()).thenReturn("SUPPLIER_API");
        properties.setTimeout(Duration.ofMillis(100));
        properties.setMaxConcurrentLookups(1);
        service = new PlayerVerificationServiceImpl(games, packages, client, properties);
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void returnsOnlyProviderSuppliedAccountDetailsForMatchingIdentity() {
        when(client.lookup("free-fire", request)).thenReturn(result(PlayerVerificationStatus.VERIFIED,
                "00555555", null, "Returned by supplier", "SG"));
        PlayerVerifyResponse response = service.verify("free-fire", new PlayerVerifyRequest(" 00555555 ", " "));
        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.VERIFIED);
        assertThat(response.nickname()).isEqualTo("Returned by supplier");
        assertThat(response.region()).isEqualTo("SG");
        assertThat(response.playerId()).isEqualTo("00555555");
        verify(client, times(1)).lookup("free-fire", request);
    }

    @Test
    void invalidAccountDoesNotReturnAccidentalProviderDetails() {
        when(client.lookup("free-fire", request)).thenReturn(result(PlayerVerificationStatus.INVALID,
                request.playerId(), null, "must not leak", "SG"));
        PlayerVerifyResponse response = service.verify("free-fire", request);
        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.INVALID);
        assertThat(response.nickname()).isNull();
        assertThat(response.region()).isNull();
    }

    @ParameterizedTest
    @MethodSource("unusableResults")
    void malformedOrMismatchedProviderResultsBecomeUnavailable(PlayerVerifyResponse providerResult) {
        when(client.lookup("free-fire", request)).thenReturn(providerResult);
        assertUnavailable(service.verify("free-fire", request));
    }

    static java.util.stream.Stream<PlayerVerifyResponse> unusableResults() {
        return java.util.stream.Stream.of(null,
                result(PlayerVerificationStatus.VERIFIED, "99999999", null, "Wrong account", "SG"),
                result(PlayerVerificationStatus.INVALID, "99999999", null, null, null),
                result(PlayerVerificationStatus.VERIFIED, "00555555", "1234", "Wrong zone", "SG"),
                result(PlayerVerificationStatus.VERIFIED, "00555555", null, null, "SG"),
                result(PlayerVerificationStatus.VERIFIED, "00555555", null, "   ", "SG"),
                result(PlayerVerificationStatus.VERIFIED, "00555555", null, "x".repeat(201), "SG"),
                result(PlayerVerificationStatus.VERIFIED, "00555555", null, "name\nforged log", "SG"),
                result(null, "00555555", null, "name", "SG"));
    }

    @Test
    void providerExceptionIsSanitizedAndNeverRetried() {
        when(client.lookup("free-fire", request)).thenThrow(new IllegalStateException("credential-must-stay-private"));
        PlayerVerifyResponse response = service.verify("free-fire", request);
        assertUnavailable(response);
        assertThat(response.toString()).doesNotContain("credential-must-stay-private");
        verify(client, times(1)).lookup("free-fire", request);
    }

    @Test
    void timeoutCancelsLookupAndReturnsUnavailable() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(client.lookup("free-fire", request)).thenAnswer(invocation -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return null;
        });
        assertUnavailable(service.verify("free-fire", request));
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        verify(client, times(1)).lookup("free-fire", request);
    }

    @Test
    void interruptionIgnoringProviderCannotCreateUnboundedWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        when(client.lookup("free-fire", request)).thenAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* simulates broken adapter */ }
            }
            return null;
        });
        try {
            assertUnavailable(service.verify("free-fire", request));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertUnavailable(service.verify("free-fire", request));
            verify(client, times(1)).lookup("free-fire", request);
        } finally { release.countDown(); }
    }

    @Test
    void unsupportedLookupIsExplicitAndDoesNotCallProvider() {
        when(client.supports("free-fire")).thenReturn(false);
        assertUnavailable(service.verify("free-fire", request));
        verify(client, never()).lookup(anyString(), any());
    }

    @Test
    void fulfillmentUsesPackageGameSlugAndStoredIds() {
        UUID packageId = UUID.randomUUID();
        Game game = games.findBySlug("free-fire").orElseThrow();
        GamePackage pack = new GamePackage();
        pack.setGameId(game.getId());
        when(packages.findById(packageId)).thenReturn(Optional.of(pack));
        when(games.findById(game.getId())).thenReturn(Optional.of(game));
        when(client.lookup("free-fire", request)).thenReturn(result(PlayerVerificationStatus.VERIFIED,
                request.playerId(), null, "Supplier name", null));
        assertThat(service.revalidateForFulfillment(packageId, request.playerId(), null)).get()
                .extracting(PlayerVerifyResponse::status).isEqualTo(PlayerVerificationStatus.VERIFIED);
        when(client.supports("free-fire")).thenReturn(false);
        assertThat(service.revalidateForFulfillment(packageId, request.playerId(), null)).isEmpty();
    }

    @Test
    void missingCatalogFailsClosedDuringFulfillment() {
        assertThat(service.revalidateForFulfillment(UUID.randomUUID(), request.playerId(), null)).get()
                .extracting(PlayerVerifyResponse::status).isEqualTo(PlayerVerificationStatus.UNAVAILABLE);
    }

    private static PlayerVerifyResponse result(PlayerVerificationStatus status, String playerId,
            String zoneId, String nickname, String region) {
        return new PlayerVerifyResponse(status, playerId, zoneId, nickname, region, "SUPPLIER_API");
    }

    private void assertUnavailable(PlayerVerifyResponse response) {
        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.UNAVAILABLE);
        assertThat(response.nickname()).isNull();
        assertThat(response.region()).isNull();
        assertThat(response.playerId()).isEqualTo(request.playerId());
    }
}
