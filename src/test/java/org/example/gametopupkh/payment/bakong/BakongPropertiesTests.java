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
}
