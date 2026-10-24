package org.example.gametopupkh.payment.aba;

import java.math.BigDecimal;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.aba.dto.AbaCallbackRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbaPayWayCallbackVerifierTests {

    private static final String SECRET = "callback-secret";

    @Test
    void unsignedCallbacksAreAcceptedOnlyWhenNoSecretIsConfigured() {
        assertThatCode(() -> new AbaPayWayCallbackVerifier("").verify(callback(null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AbaPayWayCallbackVerifier(SECRET).verify(callback(null)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void invalidSignaturesAreRejected() {
        AbaPayWayCallbackVerifier verifier = new AbaPayWayCallbackVerifier(SECRET);

        assertThatThrownBy(() -> verifier.verify(callback("not-a-signature")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .hasMessageContaining("signature");
    }

    @Test
    void canonicalAndPipeSeparatedPayloadsAreAccepted() {
        AbaPayWayCallbackVerifier verifier = new AbaPayWayCallbackVerifier(SECRET);

        assertThatCode(() -> verifier.verify(callback(signature(
                "order-1" + "APPROVED" + "10.5" + "USD"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(callback(signature(
                "order-1|APPROVED|10.5|USD"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(callback(signature(
                "order-1|10.5|APPROVED|USD"))))
                .doesNotThrowAnyException();
    }

    private String signature(String payload) {
        return AbaPayWayHashService.hmacSha512Base64(SECRET, payload);
    }

    private AbaCallbackRequest callback(String hash) {
        return new AbaCallbackRequest("order-1", "event-1", "APPROVED",
                new BigDecimal("10.50"), "USD", hash);
    }
}
