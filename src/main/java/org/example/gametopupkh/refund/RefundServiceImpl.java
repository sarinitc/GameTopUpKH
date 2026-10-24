package org.example.gametopupkh.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.FulfillmentRepository;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundServiceImpl implements RefundService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_REFERENCE_LENGTH = 255;
    private static final int MAX_HISTORY_NOTE_LENGTH = 255;

    private final RefundRepository refundRepository;
    private final FulfillmentRepository fulfillmentRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final UserRepository userRepository;

    public RefundServiceImpl(
            RefundRepository refundRepository,
            FulfillmentRepository fulfillmentRepository,
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            UserRepository userRepository) {
        this.refundRepository = refundRepository;
        this.fulfillmentRepository = fulfillmentRepository;
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public RefundResponse requestRefund(UUID paymentId, UUID adminId, RefundRequest request) {
        User admin = requireActiveAdmin(adminId);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> paymentNotFound(paymentId));

        Optional<PaymentRefund> existing = refundRepository.findByPaymentId(paymentId);
        if (existing.isPresent()) {
            return RefundResponse.from(existing.get());
        }

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw conflict("PAYMENT_NOT_REFUNDABLE",
                    "Only a completed payment can be refunded");
        }

        Order order = orderRepository.findByIdForUpdate(payment.getOrderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "Order " + payment.getOrderId() + " was not found"));
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw conflict("ORDER_ALREADY_REFUNDED",
                    "The order has already been refunded");
        }
        if (fulfillmentRepository.findByOrderId(order.getId())
                .filter(fulfillment -> fulfillment.getStatus() == FulfillmentStatus.PROCESSING)
                .isPresent()) {
            throw conflict("DELIVERY_UNRESOLVED",
                    "The supplier delivery outcome must be resolved before requesting a refund");
        }

        BigDecimal amount = request == null || request.amount() == null
                ? payment.getAmount()
                : request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REFUND_AMOUNT_INVALID",
                    "The refund amount must be greater than zero");
        }
        if (amount.compareTo(payment.getAmount()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REFUND_AMOUNT_EXCEEDED",
                    "The refund amount cannot exceed the payment amount");
        }

        PaymentRefund refund = new PaymentRefund();
        refund.setPaymentId(payment.getId());
        refund.setOrderId(payment.getOrderId());
        refund.setProvider(payment.getProvider());
        refund.setStatus(RefundStatus.PENDING);
        refund.setAmount(amount);
        refund.setCurrency(payment.getCurrency());
        refund.setReason(truncate(request == null ? null : normalize(request.reason()),
                MAX_REASON_LENGTH));
        refund.setRequestedBy(admin.getId());
        refund = refundRepository.save(refund);

        if (payment.getProvider() == PaymentProvider.MOCK) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setProviderReference(truncate("MOCK-REFUND-" + payment.getReference(),
                    MAX_REFERENCE_LENGTH));
            refund.setCompletedAt(Instant.now());
            refund = refundRepository.save(refund);
            if (amount.compareTo(payment.getAmount()) == 0) {
                transitionOrder(order, OrderStatus.REFUNDED,
                        "Refund " + refund.getId() + " completed via mock provider");
            }
        }
        // ABA refunds stay PENDING until the provider confirms the merchant-approved
        // refund. Recording the request must never mark the order as refunded by itself.

        return RefundResponse.from(refund);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundResponse> findAll(RefundStatus status) {
        List<PaymentRefund> refunds = status == null
                ? refundRepository.findAllByOrderByCreatedAtDesc()
                : refundRepository.findAllByStatusOrderByCreatedAtDesc(status);
        return refunds.stream().map(RefundResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RefundResponse findById(UUID refundId) {
        return RefundResponse.from(refundRepository.findById(refundId)
                .orElseThrow(() -> refundNotFound(refundId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundResponse> findByOrder(UUID orderId, UUID requesterId, boolean admin) {
        Order order = orderRepository.findWithUserById(orderId)
                .orElseThrow(() -> orderNotFound(orderId));
        if (!admin && (order.getUser() == null || !order.getUser().getId().equals(requesterId))) {
            throw orderNotFound(orderId);
        }
        return refundRepository.findAllByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(RefundResponse::from)
                .toList();
    }

    private User requireActiveAdmin(UUID userId) {
        User user = userRepository.findWithRolesById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Authentication is required to access this resource"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                    "The account is " + user.getAccountStatus().name().toLowerCase(Locale.ROOT));
        }
        if (!user.getRoleNames().contains(ADMIN_ROLE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "The authenticated user is not allowed to request refunds");
        }
        return user;
    }

    private void transitionOrder(Order order, OrderStatus target, String note) {
        if (order.getStatus() == target) {
            return;
        }
        order.setStatus(target);
        orderRepository.save(order);

        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrderId(order.getId());
        history.setStatus(target);
        history.setNote(truncate(note, MAX_HISTORY_NOTE_LENGTH));
        orderStatusHistoryRepository.save(history);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private ApiException orderNotFound(UUID orderId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                "Order " + orderId + " was not found");
    }

    private ApiException paymentNotFound(UUID paymentId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                "Payment " + paymentId + " was not found");
    }

    private ApiException refundNotFound(UUID refundId) {
        return new ApiException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND",
                "Refund " + refundId + " was not found");
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
