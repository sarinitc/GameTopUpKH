package org.example.gametopupkh.payment.bakong;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class BakongConfigurationDiagnosticsTests {

    @Test
    void reportsSandboxConfigurationWithoutLeakingTheToken(CapturedOutput output) {
        BakongProperties properties = properties(
                "sandbox", true, BakongProperties.DEFAULT_SANDBOX_BASE_URL,
                "super-secret-token", "merchant@bank");

        new BakongConfigurationDiagnostics(properties).afterPropertiesSet();

        assertThat(output)
                .contains("Bakong configuration: environment=sandbox, enabled=true, "
                        + "baseUrlHost=sit-api-bakong.nbc.org.kh, tokenPresent=true, accountIdPresent=true")
                .doesNotContain("super-secret-token")
                .doesNotContain("merchant@bank");
    }

    @Test
    void warnsWhenSandboxModePointsAtTheProductionHost(CapturedOutput output) {
        BakongProperties properties = properties(
                "sandbox", true, BakongProperties.DEFAULT_BASE_URL,
                "token", "merchant@bank");

        new BakongConfigurationDiagnostics(properties).afterPropertiesSet();

        assertThat(output)
                .contains("environment=sandbox")
                .contains("is a production endpoint");
    }

    @Test
    void warnsWhenProductionModePointsAtTheSandboxHost(CapturedOutput output) {
        BakongProperties properties = properties(
                "production", false, BakongProperties.DEFAULT_SANDBOX_BASE_URL,
                "token", "merchant@bank");

        new BakongConfigurationDiagnostics(properties).afterPropertiesSet();

        assertThat(output)
                .contains("environment=production, enabled=false")
                .contains("is a sandbox endpoint");
    }

    private static BakongProperties properties(
            String environment, boolean enabled, String baseUrl, String token, String accountId) {
        return new BakongProperties(
                environment, enabled, baseUrl, token, accountId,
                "GameTopUp-KH", "Phnom Penh", 10,
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);
    }
}
