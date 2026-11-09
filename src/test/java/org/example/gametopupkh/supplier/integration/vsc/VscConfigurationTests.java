package org.example.gametopupkh.supplier.integration.vsc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VscConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VscConfiguration.class);

    @Test
    void defaultDisabledDoesNotCreateAnyOutboundClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(VscSupplierClient.class);
        });
    }

    @Test
    void enablingWithoutProviderConfirmationFailsClosed() {
        runner.withPropertyValues("supplier.vsc.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("provider confirmation");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout=0s", "connect-timeout=11s", "read-timeout=-1s", "read-timeout=31s"})
    void invalidTimeoutLimitsCannotStart(String property) {
        runner.withPropertyValues("supplier.vsc." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "line\nbreak", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidMerchantNameCannotStart(String merchantName) {
        runner.withPropertyValues("supplier.vsc.merchant-name=" + merchantName)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "line\nbreak"})
    void missingOrMalformedKeyIsRejectedWithoutEchoingIt(String key) {
        assertThatThrownBy(() -> new VscSupplierClient(RestClient.create(), key, "GameTopUp-KH"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("VSC requires a rotated server environment credential")
                .hasNoCause();
    }

    @Test
    void controlCharactersInMerchantNameAreRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> new VscSupplierClient(RestClient.create(), "k", "Shop\r\nName"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("VSC requires a control-character-free merchant name of at most 100 characters")
                .hasNoCause();
    }
}
