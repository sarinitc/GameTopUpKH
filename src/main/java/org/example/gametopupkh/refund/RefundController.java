package org.example.gametopupkh.refund;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class RefundController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/api/v1/admin/payments/{id}/refunds")
    public ResponseEntity<ApiResponse<RefundResponse>> request(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal,
            @Validated @RequestBody(required = false) RefundRequest request) {
        RefundResponse refund = refundService.requestRefund(id, currentUserId(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(refund));
    }

    @GetMapping("/api/v1/admin/refunds")
    public ResponseEntity<ApiResponse<List<RefundResponse>>> list(
            @RequestParam(required = false) RefundStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(refundService.findAll(status)));
    }

    @GetMapping("/api/v1/admin/refunds/{id}")
    public ResponseEntity<ApiResponse<RefundResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(refundService.findById(id)));
    }

    @GetMapping("/api/v1/orders/{id}/refunds")
    public ResponseEntity<ApiResponse<List<RefundResponse>>> listForOrder(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                refundService.findByOrder(id, currentUserId(principal), isAdmin(principal))));
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
