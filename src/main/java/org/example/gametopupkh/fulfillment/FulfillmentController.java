package org.example.gametopupkh.fulfillment;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.order.OrderService;
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
public class FulfillmentController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final FulfillmentService fulfillmentService;
    private final OrderService orderService;

    public FulfillmentController(FulfillmentService fulfillmentService, OrderService orderService) {
        this.fulfillmentService = fulfillmentService;
        this.orderService = orderService;
    }

    @GetMapping("/api/v1/orders/{orderId}/fulfillment")
    public ResponseEntity<ApiResponse<FulfillmentResponse>> getForOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal UserDetails principal) {
        orderService.findById(orderId, currentUserId(principal), isAdmin(principal));
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.findResponseByOrderId(orderId)));
    }

    @GetMapping("/api/v1/admin/fulfillments")
    public ResponseEntity<ApiResponse<List<FulfillmentResponse>>> list(
            @RequestParam(required = false) FulfillmentStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.findAllResponses(status)));
    }

    @GetMapping("/api/v1/admin/fulfillments/{id}")
    public ResponseEntity<ApiResponse<FulfillmentResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.findResponseById(id)));
    }

    @PostMapping("/api/v1/admin/fulfillments/{id}/retry")
    public ResponseEntity<ApiResponse<FulfillmentResponse>> retry(@PathVariable UUID id) {
        fulfillmentService.retryFulfillment(id);
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.findResponseById(id)));
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
