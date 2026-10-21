package org.example.gametopupkh.payment.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory gateway used for local development and tests. Payments are approved
 * as soon as they are queried, so the full order flow can be exercised without
 * a real provider.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    public static final String NAME = "mock";

    private final Map<String, PaymentIntent> intents = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PaymentIntent createPayment(PaymentRequest request) {
        String reference = NAME + "_" + UUID.randomUUID().toString().replace("-", "");
        PaymentIntent intent = new PaymentIntent(
                NAME,
                reference,
                PaymentStatus.PENDING,
                request.amount(),
                request.currency(),
                "/api/v1/payments/mock/" + reference + "/confirm",
                Instant.now());
        intents.put(reference, intent);
        return intent;
    }

    @Override
    public PaymentIntent checkStatus(String gatewayReference) {
        PaymentIntent intent = intents.get(gatewayReference);
        if (intent == null) {
            throw new PaymentGatewayException(NAME, "Unknown payment reference: " + gatewayReference);
        }
        PaymentIntent completed = new PaymentIntent(
                intent.gateway(),
                intent.gatewayReference(),
                PaymentStatus.COMPLETED,
                intent.amount(),
                intent.currency(),
                intent.redirectUrl(),
                intent.createdAt());
        intents.put(gatewayReference, completed);
        return completed;
    }
}
