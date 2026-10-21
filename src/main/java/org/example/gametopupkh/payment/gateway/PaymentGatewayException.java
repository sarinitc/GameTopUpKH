package org.example.gametopupkh.payment.gateway;

/**
 * Raised when a payment gateway call fails or returns an unusable response.
 */
public class PaymentGatewayException extends RuntimeException {

    private final String gateway;

    public PaymentGatewayException(String gateway, String message) {
        super(message);
        this.gateway = gateway;
    }

    public PaymentGatewayException(String gateway, String message, Throwable cause) {
        super(message, cause);
        this.gateway = gateway;
    }

    public String getGateway() {
        return gateway;
    }
}
