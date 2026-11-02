package org.example.gametopupkh.payment.bakong;

import java.time.Instant;

import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongKhqrResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bakong KHQR Payment Gateway adapter implementing the {@link PaymentGateway} abstraction.
 * Active when {@code payment.gateway=bakong}.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "bakong")
public class BakongPaymentGateway implements PaymentGateway {

    public static final String NAME = "bakong";

    private final BakongKhqrService khqrService;
    private final BakongApiClient apiClient;
    private final BakongPaymentMapper mapper;

    public BakongPaymentGateway(
            BakongKhqrService khqrService,
            BakongApiClient apiClient,
            BakongPaymentMapper mapper) {
        this.khqrService = khqrService;
        this.apiClient = apiClient;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PaymentIntent createPayment(PaymentRequest request) {
        try {
            BakongKhqrResponse response = khqrService.generateDynamicKhqr(
                    request.amount(),
                    request.currency(),
                    request.transactionId()
            );

            return new PaymentIntent(
                    NAME,
                    request.transactionId(),
                    PaymentStatus.PENDING,
                    request.amount(),
                    request.currency(),
                    null,
                    Instant.now(),
                    response.qr(),
                    null,
                    response.expiresAt()
            );
        } catch (Exception e) {
            throw new PaymentGatewayException(NAME, "Failed to create Bakong KHQR payment: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentIntent checkStatus(String gatewayReference) {
        try {
            // Note: gatewayReference can be the MD5 or transaction reference.
            // Bakong API verifies by MD5 hash.
            BakongCheckTransactionResponse response = apiClient.checkTransaction(gatewayReference);
            return mapper.toIntent(response, gatewayReference, null, null);
        } catch (Exception e) {
            throw new PaymentGatewayException(NAME, "Failed to check Bakong payment status: " + e.getMessage(), e);
        }
    }
}
