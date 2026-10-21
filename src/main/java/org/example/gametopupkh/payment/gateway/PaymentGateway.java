package org.example.gametopupkh.payment.gateway;

/**
 * Abstraction over an external payment provider.
 * <p>
 * Implementations are selected with the {@code payment.gateway} property; only one
 * implementation is active at a time.
 */
public interface PaymentGateway {

    /**
     * Short name of the gateway, persisted alongside the payment record.
     */
    String name();

    /**
     * Starts a payment and returns the intent to fulfil it.
     *
     * @throws PaymentGatewayException when the provider rejects the request
     */
    PaymentIntent createPayment(PaymentRequest request);

    /**
     * Looks up the current state of a payment started through this gateway.
     *
     * @throws PaymentGatewayException when the provider cannot be reached
     */
    PaymentIntent checkStatus(String gatewayReference);
}
