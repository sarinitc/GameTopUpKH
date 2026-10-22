package org.example.gametopupkh.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.supplier.SupplierClient;
import org.example.gametopupkh.fulfillment.supplier.SupplierClient.SupplierException;
import org.example.gametopupkh.fulfillment.supplier.SupplierClient.SupplierRequest;
import org.example.gametopupkh.fulfillment.supplier.SupplierClient.SupplierResult;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentServiceImpl implements FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentServiceImpl.class);
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_HISTORY_NOTE_LENGTH = 255;

    private final FulfillmentRepository fulfillmentRepository;
    private final FulfillmentAttemptRepository attemptRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final SupplierClient supplierClient;
    private final int maxAttempts;

    public FulfillmentServiceImpl(
            FulfillmentRepository fulfillmentRepository,
            FulfillmentAttemptRepository attemptRepository,
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            SupplierClient supplierClient,
            @Value("${supplier.max-attempts:3}") int maxAttempts) {
        this.fulfillmentRepository = fulfillmentRepository;
        this.attemptRepository = attemptRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.supplierClient = supplierClient;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    @Transactional
    public Fulfillment fulfillOrder(UUID orderId) {
        Order order = requireOrder(orderId);
        Optional<Fulfillment> existing = fulfillmentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return resumeExisting(order, existing.get());
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            return createAlreadyCompleted(order);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw conflict("ORDER_NOT_FULFILLABLE",
                    "The order is not paid and cannot be fulfilled");
        }

        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setOrderId(orderId);
        fulfillment.setStatus(FulfillmentStatus.PENDING);
        fulfillment = fulfillmentRepository.save(fulfillment);
        return runAttempts(order, fulfillment);
    }

    @Override
    @Transactional
    public Fulfillment retry(UUID orderId) {
        Order order = requireOrder(orderId);
        Fulfillment fulfillment = fulfillmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND",
                        "Fulfillment for order " + orderId + " was not found"));
        if (fulfillment.getStatus() == FulfillmentStatus.SUCCEEDED
                || order.getStatus() == OrderStatus.COMPLETED) {
            return fulfillment;
        }
        if (order.getStatus() != OrderStatus.PAID
                && order.getStatus() != OrderStatus.PROCESSING
                && order.getStatus() != OrderStatus.NEEDS_REVIEW) {
            throw conflict("ORDER_NOT_FULFILLABLE",
                    "The order is not in a state that can be fulfilled");
        }
        return runAttempts(order, fulfillment);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Fulfillment> findByOrderId(UUID orderId) {
        return fulfillmentRepository.findByOrderId(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentAttempt> findAttempts(UUID orderId) {
        Fulfillment fulfillment = fulfillmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND",
                        "Fulfillment for order " + orderId + " was not found"));
        return attemptRepository.findAllByFulfillmentIdOrderByAttemptNumberAsc(fulfillment.getId());
    }

    private Fulfillment resumeExisting(Order order, Fulfillment fulfillment) {
        return switch (fulfillment.getStatus()) {
            case SUCCEEDED -> fulfillment;
            case PROCESSING, FAILED -> fulfillment;
            case PENDING -> runAttempts(order, fulfillment);
        };
    }

    private Fulfillment createAlreadyCompleted(Order order) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setOrderId(order.getId());
        fulfillment.setStatus(FulfillmentStatus.SUCCEEDED);
        fulfillment.setCompletedAt(Instant.now());
        return fulfillmentRepository.save(fulfillment);
    }

    private Fulfillment runAttempts(Order order, Fulfillment fulfillment) {
        Instant now = Instant.now();
        if (fulfillment.getStartedAt() == null) {
            fulfillment.setStartedAt(now);
        }
        fulfillment.setStatus(FulfillmentStatus.PROCESSING);
        fulfillment = fulfillmentRepository.save(fulfillment);
        transitionOrder(order, OrderStatus.PROCESSING,
                "Fulfillment started with " + supplierClient.name());

        SupplierRequest request = requestFor(order);
        int attemptLimit = fulfillment.getAttemptCount() + maxAttempts;
        while (fulfillment.getAttemptCount() < attemptLimit) {
            int attemptNumber = fulfillment.getAttemptCount() + 1;
            fulfillment.setAttemptCount(attemptNumber);
            fulfillment = fulfillmentRepository.save(fulfillment);

            FulfillmentAttempt attempt = new FulfillmentAttempt();
            attempt.setFulfillmentId(fulfillment.getId());
            attempt.setAttemptNumber(attemptNumber);
            attempt.setStatus(FulfillmentAttempt.Status.PENDING);
            attempt = attemptRepository.save(attempt);

            SupplierResult result;
            try {
                result = supplierClient.fulfill(request);
            } catch (SupplierException e) {
                result = SupplierResult.failure("The supplier rejected the delivery");
                log.warn("Supplier {} rejected fulfillment attempt {} for order {}",
                        supplierClient.name(), attemptNumber, order.getId());
            } catch (RuntimeException e) {
                result = SupplierResult.failure("The supplier call failed unexpectedly");
                log.error("Supplier {} failed during fulfillment attempt {} for order {} ({})",
                        supplierClient.name(), attemptNumber, order.getId(), e.getClass().getSimpleName());
            }

            if (result != null && result.success()) {
                return markSucceeded(order, fulfillment, attempt, result);
            }

            markAttemptFailed(fulfillment, attempt, result);
        }

        return markFailed(order, fulfillment);
    }

    private Fulfillment markSucceeded(Order order, Fulfillment fulfillment,
            FulfillmentAttempt attempt, SupplierResult result) {
        String reference = truncate(result.supplierReference(), MAX_MESSAGE_LENGTH);
        String message = messageOrDefault(result.message(), "Top-up delivered");

        attempt.setStatus(FulfillmentAttempt.Status.SUCCESS);
        attempt.setSupplierReference(reference);
        attempt.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        attemptRepository.save(attempt);

        Instant now = Instant.now();
        fulfillment.setStatus(FulfillmentStatus.SUCCEEDED);
        fulfillment.setSupplierReference(reference);
        fulfillment.setLastError(null);
        fulfillment.setCompletedAt(now);
        fulfillment = fulfillmentRepository.save(fulfillment);

        transitionOrder(order, OrderStatus.COMPLETED,
                "Fulfillment completed" + (reference == null ? "" : " (" + reference + ")"));
        return fulfillment;
    }

    private void markAttemptFailed(Fulfillment fulfillment, FulfillmentAttempt attempt,
            SupplierResult result) {
        String message = result == null
                ? "The supplier returned no result"
                : messageOrDefault(result.message(), "The supplier rejected the delivery");

        attempt.setStatus(FulfillmentAttempt.Status.FAILED);
        attempt.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        attemptRepository.save(attempt);

        fulfillment.setLastError(truncate(message, MAX_MESSAGE_LENGTH));
        fulfillmentRepository.save(fulfillment);
    }

    private Fulfillment markFailed(Order order, Fulfillment fulfillment) {
        Instant now = Instant.now();
        fulfillment.setStatus(FulfillmentStatus.FAILED);
        fulfillment.setCompletedAt(now);
        fulfillment = fulfillmentRepository.save(fulfillment);

        transitionOrder(order, OrderStatus.NEEDS_REVIEW,
                "Supplier fulfillment failed after " + fulfillment.getAttemptCount()
                        + " attempt(s): " + fulfillment.getLastError());
        return fulfillment;
    }

    private SupplierRequest requestFor(Order order) {
        return new SupplierRequest(
                order.getId(),
                order.getOrderNumber(),
                order.getPackageId(),
                order.getGameNameSnapshot(),
                order.getPackageNameSnapshot(),
                order.getPlayerId(),
                order.getZoneId(),
                order.getQuantity());
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

    private Order requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "Order " + orderId + " was not found"));
    }

    private String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
