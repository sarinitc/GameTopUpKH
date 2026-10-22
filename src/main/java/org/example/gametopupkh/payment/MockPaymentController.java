package org.example.gametopupkh.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-development helpers for completing mock payments. The controller is only
 * registered while the mock gateway is active, so it is absent in ABA deployments.
 */
@RestController
@ConditionalOnProperty(name = "payment.gateway", havingValue = "mock", matchIfMissing = true)
@Tag(name = "Mock payments", description = "Complete mock payments while payment.gateway=mock")
public class MockPaymentController {

    private final PaymentService paymentService;

    public MockPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/v1/dev/payments/{paymentId}/success")
    @Operation(summary = "Mark a mock payment as successful",
            description = "Accepts a payment id, local reference or gateway reference.")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> succeed(@PathVariable String paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.confirmMockPayment(paymentId)));
    }

    @RequestMapping(value = "/api/v1/payments/mock/{reference}/confirm",
            method = { RequestMethod.GET, RequestMethod.POST })
    @Operation(summary = "Confirm a mock payment by its gateway reference",
            description = "Matches the redirect URL returned by the mock gateway.")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> confirm(@PathVariable String reference) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.confirmMockPayment(reference)));
    }
}
