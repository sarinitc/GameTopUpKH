package org.example.gametopupkh.payment;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/v1/orders/{orderId}/payments/aba")
    public ResponseEntity<ApiResponse<CreatePaymentResponse>> createAbaPayment(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal UserDetails principal) {
        CreatePaymentResponse payment = paymentService.createAbaPayment(orderId, currentUserId(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(payment));
    }

    @GetMapping("/api/v1/payments/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getPayment(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal UserDetails principal) {
        PaymentStatusResponse payment = paymentService.getPayment(
                paymentId, currentUserId(principal), isAdmin(principal));
        return ResponseEntity.ok(ApiResponse.ok(payment));
    }

    @GetMapping("/api/v1/admin/payments")
    public ResponseEntity<ApiResponse<List<PaymentStatusResponse>>> listAll(
            @RequestParam(required = false) PaymentStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.findAll(status)));
    }

    @PostMapping("/api/v1/admin/payments/{paymentId}/reconcile")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> reconcile(
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal UserDetails principal) {
        PaymentStatusResponse payment = paymentService.reconcile(paymentId, currentUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(payment));
    }

    private UUID currentUserId(UserDetails principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Authentication is required to access this resource");
        }
        try {
            return UUID.fromString(principal.getUsername());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "The authenticated principal is not a valid user id");
        }
    }

    private boolean isAdmin(UserDetails principal) {
        if (principal == null) {
            return false;
        }
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
