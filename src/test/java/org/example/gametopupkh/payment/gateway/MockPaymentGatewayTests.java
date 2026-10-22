package org.example.gametopupkh.payment.gateway;

import java.math.BigDecimal;

import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGatewayTests {

    @Test
    void checkingAPendingPaymentCompletesItWithoutChangingItsDetails() {
        MockPaymentGateway gateway = new MockPaymentGateway();
        PaymentIntent pending = gateway.createPayment(new PaymentRequest(
                "order-1", new BigDecimal("12.50"), "USD", "player-1",
                "https://example.test/callback", "Test order"));

        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.amount()).isEqualByComparingTo("12.50");
        assertThat(pending.currency()).isEqualTo("USD");

        PaymentIntent completed = gateway.checkStatus(pending.gatewayReference());

        assertThat(completed.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(completed.gatewayReference()).isEqualTo(pending.gatewayReference());
        assertThat(completed.amount()).isEqualByComparingTo("12.50");
        assertThat(completed.currency()).isEqualTo("USD");
        assertThat(completed.createdAt()).isEqualTo(pending.createdAt());
        assertThat(gateway.checkStatus(pending.gatewayReference())).isEqualTo(completed);
    }

    @Test
    void checkingAnUnknownPaymentFails() {
        MockPaymentGateway gateway = new MockPaymentGateway();

        assertThatThrownBy(() -> gateway.checkStatus("unknown-reference"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Unknown payment reference");
    }
}
