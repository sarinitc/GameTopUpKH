package org.example.gametopupkh.payment.gateway;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "payment.gateway=aba")
class AbaPayWayGatewayTests {

    @Autowired
    private List<PaymentGateway> gateways;

    @Autowired
    private AbaPayWayGateway gateway;

    @Test
    void abaGatewayIsTheOnlyActiveImplementation() {
        assertThat(gateways).hasSize(1);
        assertThat(gateways.get(0)).isInstanceOf(AbaPayWayGateway.class);
    }

    @Test
    void failsFastWhenApiKeyIsMissing() {
        PaymentRequest request = new PaymentRequest(
                "order-1",
                new java.math.BigDecimal("10.00"),
                "USD",
                "player-1",
                "http://localhost:8082/api/v1/payments/callback",
                "Test order");
        assertThatThrownBy(() -> gateway.createPayment(request))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("payment.aba.api-key");
    }
}
