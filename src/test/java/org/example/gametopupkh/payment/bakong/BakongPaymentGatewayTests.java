package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.time.Instant;

import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse.BakongTransactionData;
import org.example.gametopupkh.payment.bakong.dto.BakongKhqrResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BakongPaymentGatewayTests {

    private BakongKhqrService khqrService;
    private BakongApiClient apiClient;
    private BakongPaymentMapper mapper;
    private BakongPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        khqrService = mock(BakongKhqrService.class);
        apiClient = mock(BakongApiClient.class);
        mapper = new BakongPaymentMapper();
        gateway = new BakongPaymentGateway(khqrService, apiClient, mapper);
    }

    @Test
    @DisplayName("Gateway name is bakong")
    void gatewayNameIsBakong() {
        assertThat(gateway.name()).isEqualTo("bakong");
    }

    @Test
    @DisplayName("createPayment returns PaymentIntent with QR and expiresAt")
    void createPaymentReturnsIntent() {
        PaymentRequest request = new PaymentRequest(
                "REF-001",
                new BigDecimal("10.00"),
                "USD",
                "customer-1",
                "http://localhost:8082/callback",
                "Order 123"
        );
        Instant expiresAt = Instant.now().plusSeconds(600);
        BakongKhqrResponse response = new BakongKhqrResponse(
                "sample-khqr",
                "0123456789abcdef0123456789abcdef",
                new BigDecimal("10.00"),
                "USD",
                expiresAt,
                "REF-001"
        );
        when(khqrService.generateDynamicKhqr(new BigDecimal("10.00"), "USD", "REF-001"))
                .thenReturn(response);

        PaymentIntent intent = gateway.createPayment(request);

        assertThat(intent).isNotNull();
        assertThat(intent.gateway()).isEqualTo("bakong");
        assertThat(intent.gatewayReference()).isEqualTo("REF-001");
        assertThat(intent.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(intent.qrString()).isEqualTo("sample-khqr");
        assertThat(intent.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("checkStatus queries apiClient and maps completed intent")
    void checkStatusMapsCompletedIntent() {
        String md5 = "0123456789abcdef0123456789abcdef";
        String txHash = "hash-123456";
        BakongTransactionData data = new BakongTransactionData(
                txHash,
                "customer@devb",
                "merchant@bank",
                "USD",
                new BigDecimal("10.00"),
                "Description",
                1700000000000L,
                1700000001000L,
                "EXT-1"
        );
        when(apiClient.checkTransaction(md5))
                .thenReturn(new BakongCheckTransactionResponse(0, "Success", data));

        PaymentIntent intent = gateway.checkStatus(md5);

        assertThat(intent.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(intent.gatewayReference()).isEqualTo(txHash);
        assertThat(intent.amount()).isEqualByComparingTo("10.00");
        assertThat(intent.currency()).isEqualTo("USD");
    }
}
