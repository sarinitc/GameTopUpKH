package org.example.gametopupkh.payment.bakong;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BakongPropertiesTests {

    @ParameterizedTest
    @ValueSource(strings = {"falset", "false", "FALSE", "0", "no", "disabled", "invalid", ""})
    void invalidOrFalseValuesSafelyResolveToDisabled(String input) {
        assertThat(BakongProperties.parseBooleanLenient(input)).isFalse();

        BakongProperties props = new BakongProperties(
                "production",
                input,
                "https://api-bakong.nbc.gov.kh",
                "",
                "",
                "GameTopUp-KH",
                "Phnom Penh",
                10,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                2);

        assertThat(props.isEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "1", "yes", "on"})
    void validTrueValuesResolveToEnabled(String input) {
        assertThat(BakongProperties.parseBooleanLenient(input)).isTrue();
    }

    @Test
    void sandboxEnvironmentDefaultsToSitBaseUrlWhenBaseUrlIsMissing() {
        BakongProperties props = new BakongProperties(
                "sandbox", false, "", "token", "acc@bank",
                "GameTopUp-KH", "Phnom Penh", 10,
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);

        assertThat(props.baseUrl()).isEqualTo(BakongProperties.DEFAULT_SANDBOX_BASE_URL);
        assertThat(props.isSandbox()).isTrue();
    }

    @Test
    void productionEnvironmentDefaultsToProductionBaseUrlWhenBaseUrlIsMissing() {
        BakongProperties props = new BakongProperties(
                "production", false, "", "token", "acc@bank",
                "GameTopUp-KH", "Phnom Penh", 10,
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);

        assertThat(props.baseUrl()).isEqualTo(BakongProperties.DEFAULT_BASE_URL);
        assertThat(props.isProduction()).isTrue();
    }

    @Test
    void explicitBaseUrlIsPreserved() {
        BakongProperties props = new BakongProperties(
                "sandbox", false, "https://example.com", "token", "acc@bank",
                "GameTopUp-KH", "Phnom Penh", 10,
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);

        assertThat(props.baseUrl()).isEqualTo("https://example.com");
    }

    @Test
    void enabledWithMissingTokenLogsWarningAndDoesNotCrashStartup() {
        BakongProperties props = new BakongProperties(
                "production",
                true,
                "https://api-bakong.nbc.gov.kh",
                "", // missing token
                "merchant@bank",
                "GameTopUp-KH",
                "Phnom Penh",
                10,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                2);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.apiToken()).isEmpty();
    }

    @Test
    void enabledWithValidConfigInitializesCorrectly() {
        BakongProperties props = new BakongProperties(
                "production",
                true,
                "https://api-bakong.nbc.gov.kh",
                "live-jwt-token",
                "merchant@bank",
                "GameTopUp-KH",
                "Phnom Penh",
                10,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                2);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.apiToken()).isEqualTo("live-jwt-token");
        assertThat(props.accountId()).isEqualTo("merchant@bank");
        assertThat(props.baseUrl()).isEqualTo("https://api-bakong.nbc.gov.kh");
    }
}
