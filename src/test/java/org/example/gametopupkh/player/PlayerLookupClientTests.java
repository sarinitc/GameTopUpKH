package org.example.gametopupkh.player;

import org.example.gametopupkh.player.config.PlayerVerificationProperties;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.integration.MockPlayerLookupClient;
import org.example.gametopupkh.player.integration.PlayerLookupClient;
import org.example.gametopupkh.player.integration.SupplierPlayerLookupClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerLookupClientTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LookupConfiguration.class);

    @Test
    void defaultAdapterCannotInventSupplierAccountDetails() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PlayerLookupClient client = context.getBean(PlayerLookupClient.class);
            assertThat(client).isInstanceOf(SupplierPlayerLookupClient.class);
            assertThat(client.supports("free-fire")).isFalse();
            assertThat(client.supports("mobile-legends")).isFalse();
            var response = client.lookup("free-fire", new PlayerVerifyRequest("001234", null));
            assertThat(response.status()).isEqualTo(PlayerVerificationStatus.UNAVAILABLE);
            assertThat(response.playerId()).isEqualTo("001234");
            assertThat(response.nickname()).isNull();
            assertThat(response.region()).isNull();
            assertThat(response.provider()).isEqualTo("SUPPLIER_API");
        });
    }

    @Test
    void devProfileAloneDoesNotEnableMockVerification() {
        contextRunner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PlayerLookupClient.class)).isInstanceOf(SupplierPlayerLookupClient.class);
        });
    }

    @Test
    void enabledDevMockReturnsOnlyExactConfiguredAccountFacts() {
        mockContext().withPropertyValues(
                "player.verification.mock-fixtures[0].game-slug=mobile-legends",
                "player.verification.mock-fixtures[0].player-id=001234",
                "player.verification.mock-fixtures[0].zone-id=0007",
                "player.verification.mock-fixtures[0].nickname=Fixture Hero",
                "player.verification.mock-fixtures[0].region=KH",
                "player.verification.mock-fixtures[0].status=VERIFIED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PlayerLookupClient client = context.getBean(PlayerLookupClient.class);
                    assertThat(client).isInstanceOf(MockPlayerLookupClient.class);
                    assertThat(client.supports("free-fire")).isTrue();
                    assertThat(client.supports("mobile-legends")).isTrue();
                    assertThat(client.supports("other-game")).isFalse();
                    var response = client.lookup("mobile-legends", new PlayerVerifyRequest("001234", "0007"));
                    assertThat(response.status()).isEqualTo(PlayerVerificationStatus.VERIFIED);
                    assertThat(response.playerId()).isEqualTo("001234");
                    assertThat(response.zoneId()).isEqualTo("0007");
                    assertThat(response.nickname()).isEqualTo("Fixture Hero");
                    assertThat(response.region()).isEqualTo("KH");
                    assertThat(response.provider()).isEqualTo("MOCK");
                    assertUnavailable(client.lookup("mobile-legends", new PlayerVerifyRequest("001234", "7")));
                    assertUnavailable(client.lookup("mobile-legends", new PlayerVerifyRequest("1234", "0007")));
                    assertUnavailable(client.lookup("free-fire", new PlayerVerifyRequest("001234", null)));
                });
    }

    @Test
    void unknownMockAccountsRemainUnavailable() {
        mockContext().run(context -> {
            assertThat(context).hasNotFailed();
            PlayerLookupClient client = context.getBean(PlayerLookupClient.class);
            assertUnavailable(client.lookup("free-fire", new PlayerVerifyRequest("123456", null)));
        });
    }

    @Test
    void explicitInvalidFixtureDoesNotReturnAccountFacts() {
        mockContext().withPropertyValues(
                "player.verification.mock-fixtures[0].game-slug=free-fire",
                "player.verification.mock-fixtures[0].player-id=987654",
                "player.verification.mock-fixtures[0].nickname=Must not leak",
                "player.verification.mock-fixtures[0].region=KH",
                "player.verification.mock-fixtures[0].status=INVALID")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var response = context.getBean(PlayerLookupClient.class)
                            .lookup("free-fire", new PlayerVerifyRequest("987654", null));
                    assertThat(response.status()).isEqualTo(PlayerVerificationStatus.INVALID);
                    assertThat(response.nickname()).isNull();
                    assertThat(response.region()).isNull();
                    assertThat(response.provider()).isEqualTo("MOCK");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test", "prod", "production", "dev,prod", "dev,production"})
    void mockEnabledOutsideIsolatedDevCannotStartAConsumer(String profiles) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profiles,
                        "player.verification.mock-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "timeout=0s", "timeout=-1s", "timeout=11s", "max-concurrent-lookups=0",
            "max-concurrent-lookups=65", "requests-per-minute=0", "global-requests-per-minute=0",
            "max-rate-limit-keys=0", "max-rate-limit-keys=100001"
    })
    void unsafeResourceLimitsFailConfigurationBinding(String property) {
        contextRunner.withPropertyValues("player.verification." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "VERIFIED"})
    void fixturesRequireExplicitUsableAccountFacts(String status) {
        mockContext().withPropertyValues(
                "player.verification.mock-fixtures[0].game-slug=free-fire",
                "player.verification.mock-fixtures[0].player-id=987654",
                "player.verification.mock-fixtures[0].status=" + status)
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner mockContext() {
        return contextRunner.withPropertyValues("spring.profiles.active=dev",
                "player.verification.mock-enabled=true");
    }

    private void assertUnavailable(org.example.gametopupkh.player.dto.PlayerVerifyResponse response) {
        assertThat(response.status()).isEqualTo(PlayerVerificationStatus.UNAVAILABLE);
        assertThat(response.nickname()).isNull();
        assertThat(response.region()).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import({PlayerVerificationProperties.class, MockPlayerLookupClient.class, SupplierPlayerLookupClient.class})
    static class LookupConfiguration {
        @Bean
        LookupConsumer lookupConsumer(PlayerLookupClient client) {
            return new LookupConsumer(client);
        }
    }

    record LookupConsumer(PlayerLookupClient client) {
    }
}
