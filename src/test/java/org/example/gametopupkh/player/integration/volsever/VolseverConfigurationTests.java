package org.example.gametopupkh.player.integration.volsever;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class VolseverConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void enabledWithKeyCreatesTheAdapter() {
        runner.withPropertyValues(
                        "player.verification.volsever.enabled=true",
                        "VOLSEVER_API_KEY=test-only-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(VolseverPlayerLookupClient.class)).hasSize(1);
                });
    }

    @Test
    void disabledDoesNotCreateTheAdapter() {
        runner.withPropertyValues(
                        "player.verification.volsever.enabled=false",
                        "VOLSEVER_API_KEY=test-only-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(VolseverPlayerLookupClient.class)).isEmpty();
                });
    }

    @Test
    void developmentMockTakesPrecedenceOverVolsever() {
        runner.withPropertyValues(
                        "player.verification.volsever.enabled=true",
                        "player.verification.mock-enabled=true",
                        "VOLSEVER_API_KEY=test-only-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(VolseverPlayerLookupClient.class)).isEmpty();
                });
    }

    @Test
    void enabledWithoutKeyFailsStartupInsteadOfSilentlyReportingUnavailable() {
        runner.withPropertyValues("player.verification.volsever.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "player.verification.volsever.base-url=http://gate.volsever.com",
            "player.verification.volsever.base-url=https://user:secret@gate.volsever.com",
            "player.verification.volsever.base-url=https://gate.volsever.com?key=secret",
            "player.verification.volsever.free-fire-path=proxy/api/game/free-fire-global",
            "player.verification.volsever.mobile-legends-path=/proxy/api/game/mobile-legends?x=1",
            "player.verification.volsever.connect-timeout=0s",
            "player.verification.volsever.read-timeout=11s"
    })
    void unsafeSettingsFailConfigurationBinding(String property) {
        runner.withPropertyValues(
                        "player.verification.volsever.enabled=true",
                        "VOLSEVER_API_KEY=test-only-key",
                        property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(VolseverConfiguration.class)
    static class TestConfiguration {
    }
}
