package org.example.gametopupkh.player.integration;

import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PlayerLookupClientDiagnosticsTests {

    @Test
    void warnsWhenOnlyTheUnavailableFallbackIsActiveAndNeverLogsTheKey(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("player.verification.volsever.enabled", "false")
                .withProperty("VOLSEVER_API_KEY", "super-secret-value");

        new PlayerLookupClientDiagnostics(new SupplierPlayerLookupClient(), environment)
                .afterPropertiesSet();

        assertThat(output)
                .contains("Player verification provider active: SUPPLIER_API")
                .contains("No live player verification provider is active")
                .doesNotContain("super-secret-value");
    }

    @Test
    void reportsVolseverWhenItSupportsTheGames(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("player.verification.volsever.enabled", "true")
                .withProperty("VOLSEVER_API_KEY", "super-secret-value");

        PlayerLookupClient volsever = new PlayerLookupClient() {
            @Override
            public boolean supports(String gameSlug) {
                return "free-fire".equals(gameSlug) || "mobile-legends".equals(gameSlug);
            }

            @Override
            public String provider() {
                return "VOLSEVER";
            }

            @Override
            public PlayerVerifyResponse lookup(String gameSlug, PlayerVerifyRequest request) {
                throw new UnsupportedOperationException("not used by diagnostics");
            }
        };

        new PlayerLookupClientDiagnostics(volsever, environment).afterPropertiesSet();

        assertThat(output)
                .contains("Player verification provider active: VOLSEVER")
                .doesNotContain("No live player verification provider is active")
                .doesNotContain("super-secret-value");
    }
}
