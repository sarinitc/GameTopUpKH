package org.example.gametopupkh.payment.aba;

import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ABA PayWay adapter. The default configuration targets the official QR and
 * check-transaction APIs; custom paths preserve legacy transport behavior.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "aba")
public class AbaPayWayGateway implements PaymentGateway {

    public static final String NAME = "aba";

    private final AbaPayWayClient client;
    private final AbaPayWayPaymentMapper mapper;

    public AbaPayWayGateway(AbaPayWayClient client, AbaPayWayPaymentMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PaymentIntent createPayment(PaymentRequest request) {
        client.requireConfigured();
        try {
            return mapper.toIntent(client.createPayment(request), request);
        } catch (Exception e) {
            throw new PaymentGatewayException(NAME, "Failed to create AbaPayWay payment: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentIntent checkStatus(String gatewayReference) {
        client.requireConfigured();
        try {
            return mapper.toIntent(
                    client.checkTransaction(new AbaCheckTransactionRequest(gatewayReference)), gatewayReference);
        } catch (Exception e) {
            throw new PaymentGatewayException(NAME, "Failed to read AbaPayWay payment: " + e.getMessage(), e);
        }
    }
}
