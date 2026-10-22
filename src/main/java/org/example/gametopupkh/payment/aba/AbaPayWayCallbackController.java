package org.example.gametopupkh.payment.aba;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.payment.PaymentService;
import org.example.gametopupkh.payment.aba.dto.AbaCallbackRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Public PayWay callback entry point. The payload is untrusted: it is signature
 * checked when a secret is configured, then used only to trigger a provider-side
 * status lookup.
 */
@RestController
public class AbaPayWayCallbackController {

    private final PaymentService paymentService;
    private final AbaPayWayCallbackVerifier verifier;

    public AbaPayWayCallbackController(PaymentService paymentService, AbaPayWayCallbackVerifier verifier) {
        this.paymentService = paymentService;
        this.verifier = verifier;
    }

    @PostMapping("/api/v1/payments/aba/callback")
    public ResponseEntity<ApiResponse<Void>> callback(@RequestBody(required = false) JsonNode payload) {
        AbaCallbackRequest callback = AbaCallbackRequest.from(payload);
        if (callback.reference() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_REFERENCE_REQUIRED",
                    "A payment reference is required");
        }
        verifier.verify(callback);
        paymentService.processCallback(callback.reference(), callback.eventKey());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
