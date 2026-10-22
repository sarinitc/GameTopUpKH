package org.example.gametopupkh.order;

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
public class OrderController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/v1/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> create(
            @AuthenticationPrincipal UserDetails principal,
            @Validated @RequestBody OrderRequest request) {
        OrderResponse order = orderService.create(currentUserId(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(order));
    }

    @GetMapping("/api/v1/orders/my")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> listMine(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.findByCustomer(currentUserId(principal))));
    }

    @GetMapping("/api/v1/orders/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        UUID requesterId = currentUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(orderService.findById(id, requesterId, isAdmin(principal))));
    }

    @GetMapping("/api/v1/admin/orders")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> listAll(
            @RequestParam(required = false) OrderStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.findAll(status)));
    }

    @GetMapping("/api/v1/admin/orders/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getByIdAsAdmin(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.findById(id, null, true)));
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
