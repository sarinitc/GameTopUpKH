package org.example.gametopupkh.supplier.integration.bay2game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Bay2GameConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Bay2GameConfiguration.class);

    @Test
    void defaultDisabledDoesNotCreateAnyOutboundClient() {
        runner.withPropertyValues("supplier.bay2game.api-key=not-an-accepted-credential-source")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(Bay2GameClient.class);
                });
    }

    @Test
    void enablingWithoutSupportConfirmationFailsClosed() {
        runner.withPropertyValues("supplier.bay2game.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("provider confirmation");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout=0s", "connect-timeout=11s", "read-timeout=-1s", "read-timeout=31s"})
    void invalidTimeoutLimitsCannotStart(String property) {
        runner.withPropertyValues("supplier.bay2game." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "line\nbreak"})
    void missingOrMalformedKeyIsRejectedWithoutEchoingIt(String key) {
        assertThatThrownBy(() -> new Bay2GameClient(RestClient.create(), key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bay2Game requires a rotated server environment credential")
                .hasNoCause();
    }
}
