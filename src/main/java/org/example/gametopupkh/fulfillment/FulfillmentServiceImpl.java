package org.example.gametopupkh.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.example.gametopupkh.player.service.PlayerVerificationService;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.repository.SupplierProductRepository;
import org.example.gametopupkh.supplier.repository.SupplierRepository;
import org.example.gametopupkh.supplier.service.SupplierSelectionService;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClientRegistry;
import org.example.gametopupkh.supplier.integration.SupplierClient.SupplierException;
import org.example.gametopupkh.supplier.integration.SupplierTopupRequest;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FulfillmentServiceImpl implements FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentServiceImpl.class);
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_REFERENCE_LENGTH = 255;
    private static final int MAX_HISTORY_NOTE_LENGTH = 255;

    private final FulfillmentRepository fulfillmentRepository;
    private final FulfillmentAttemptRepository attemptRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final SupplierClientRegistry supplierClients;
    private final SupplierSelectionService supplierSelectionService;
    private final SupplierProductRepository supplierProducts;
    private final SupplierRepository suppliers;
    private final PlayerVerificationService playerVerification;
    private final TransactionTemplate requiresNewTransaction;
    private final int maxAttempts;

    public FulfillmentServiceImpl(
            FulfillmentRepository fulfillmentRepository,
            FulfillmentAttemptRepository attemptRepository,
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            SupplierClientRegistry supplierClients,
            SupplierSelectionService supplierSelectionService,
            SupplierProductRepository supplierProducts,
            SupplierRepository suppliers,
            PlayerVerificationService playerVerification,
            PlatformTransactionManager transactionManager,
            @Value("${supplier.max-attempts:3}") int maxAttempts) {
        this.fulfillmentRepository = fulfillmentRepository;
        this.attemptRepository = attemptRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.supplierClients = supplierClients;
        this.supplierSelectionService = supplierSelectionService;
        this.supplierProducts = supplierProducts;
        this.suppliers = suppliers;
        this.playerVerification = playerVerification;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public Fulfillment fulfillOrder(UUID orderId) {
        Claim claim = requiresNewTransaction.execute(status -> claim(orderId, false));
        return claim.execute() ? run(claim) : claim.fulfillment();
    }

    @Override
    public Fulfillment retry(UUID orderId) {
        Claim claim = requiresNewTransaction.execute(status -> claim(orderId, true));
        return claim.execute() ? run(claim) : claim.fulfillment();
    }

    @Override
    public Fulfillment retryFulfillment(UUID fulfillmentId) {
        UUID orderId = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId))
                .getOrderId();
        return retry(orderId);
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

    @Override
    @Transactional(readOnly = true)
    public FulfillmentResponse findResponseByOrderId(UUID orderId) {
        Fulfillment fulfillment = fulfillmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND",
                        "Fulfillment for order " + orderId + " was not found"));
        return responseFor(fulfillment);
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentResponse findResponseById(UUID fulfillmentId) {
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        return responseFor(fulfillment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentResponse> findAllResponses(FulfillmentStatus status) {
        List<Fulfillment> fulfillments = status == null
                ? fulfillmentRepository.findAllByOrderByCreatedAtDesc()
                : fulfillmentRepository.findAllByStatusOrderByCreatedAtDesc(status);
        return fulfillments.stream().map(this::responseFor).toList();
    }

    private FulfillmentResponse responseFor(Fulfillment fulfillment) {
        List<FulfillmentAttempt> attempts =
                attemptRepository.findAllByFulfillmentIdOrderByAttemptNumberAsc(fulfillment.getId());
        return FulfillmentResponse.from(fulfillment, attempts);
    }

    private ApiException fulfillmentNotFound(UUID fulfillmentId) {
        return new ApiException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND",
                "Fulfillment " + fulfillmentId + " was not found");
    }

    private Claim claim(UUID orderId, boolean manualRetry) {
        Order order = requireOrder(orderId);
        SupplierClient supplierClient = supplierClients.getConfiguredClient();
        Optional<Fulfillment> existing = fulfillmentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            Fulfillment fulfillment = existing.get();
            return switch (fulfillment.getStatus()) {
                case SUCCEEDED, PROCESSING -> stop(fulfillment);
                case PENDING -> {
                    requirePendingResumable(order);
                    yield start(order, fulfillment, supplierClient);
                }
                case FAILED -> {
                    if (!manualRetry) {
                        yield stop(fulfillment);
                    }
                    requireRetryable(order);
                    if (!storedSelectionIsEligible(fulfillment)) {
                        yield stop(fulfillment);
                    }
                    yield start(order, fulfillment, supplierClient);
                }
            };
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            Fulfillment completed = new Fulfillment();
            completed.setOrderId(order.getId());
            completed.setStatus(FulfillmentStatus.SUCCEEDED);
            completed.setCompletedAt(Instant.now());
            return stop(fulfillmentRepository.save(completed));
        }
        requireFulfillable(order);

        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setOrderId(orderId);
        fulfillment.setStatus(FulfillmentStatus.PENDING);
        fulfillment = fulfillmentRepository.save(fulfillment);
        return start(order, fulfillment, supplierClient);
    }

    private Claim start(Order order, Fulfillment fulfillment, SupplierClient supplierClient) {
        if (fulfillment.getStartedAt() == null) {
            fulfillment.setStartedAt(Instant.now());
        }
        fulfillment.setStatus(FulfillmentStatus.PROCESSING);
        fulfillment.setCompletedAt(null);
        fulfillment.setLastError(null);
        if (fulfillment.getSupplierProductId() == null) {
            try {
                SupplierProduct selected = supplierSelectionService.selectForPackage(order.getPackageId(),
                        order.getCurrency());
                fulfillment.setSupplierId(selected.getSupplierId());
                fulfillment.setSupplierProductId(selected.getId());
                fulfillment.setSupplierSku(selected.getExternalSku());
                fulfillment.setSupplierAdapterKey(supplierClient.key());
            } catch (ApiException ex) {
                fulfillment.setLastError(truncate(describe(ex), MAX_MESSAGE_LENGTH));
                fulfillment = fulfillmentRepository.save(fulfillment);
                return stop(markFailed(order, fulfillment));
            }
        } else if (fulfillment.getSupplierAdapterKey() == null) {
            fulfillment.setSupplierAdapterKey(supplierClient.key());
        }
        fulfillment = fulfillmentRepository.save(fulfillment);
        transitionOrder(order, OrderStatus.PROCESSING,
                "Fulfillment started with " + supplierClient.name());
        return new Claim(fulfillment, requestFor(order, fulfillment), true);
    }

    private Fulfillment run(Claim claim) {
        UUID fulfillmentId = claim.fulfillment().getId();
        // The durable claim is committed before lookup, and no delivery attempt exists yet.
        String preflightFailure = preflightFailure(claim.request());
        if (preflightFailure != null) {
            return requiresNewTransaction.execute(status -> markPreflightFailed(fulfillmentId, preflightFailure));
        }
        SupplierClient supplierClient = supplierClients.getConfiguredClient();
        for (int remaining = maxAttempts; remaining > 0; remaining--) {
            BeginAttempt begun = requiresNewTransaction.execute(status -> beginAttempt(fulfillmentId));
            SupplierTopupResponse result;
            try {
                result = supplierClient.fulfill(claim.request());
            } catch (SupplierException e) {
                log.warn("Supplier {} returned an uncertain outcome for fulfillment {} attempt {}",
                        supplierClient.name(), fulfillmentId, begun.attemptNumber());
                return requiresNewTransaction.execute(status -> markUnknown(fulfillmentId, begun.attemptId(),
                        "The supplier rejected the delivery; outcome requires review"));
            } catch (RuntimeException e) {
                log.error("Supplier {} failed unexpectedly for fulfillment {} attempt {} ({})",
                        supplierClient.name(), fulfillmentId, begun.attemptNumber(), e.getClass().getSimpleName());
                return requiresNewTransaction.execute(status -> markUnknown(fulfillmentId, begun.attemptId(),
                        "The supplier call failed unexpectedly; outcome requires review"));
            }

            if (result == null) {
                return requiresNewTransaction.execute(status -> markUnknown(fulfillmentId, begun.attemptId(),
                        "The supplier returned no result; outcome requires review"));
            }

            if (result.success()) {
                if (result.supplierReference() == null || result.supplierReference().isBlank()) {
                    return requiresNewTransaction.execute(status -> markUnknown(fulfillmentId, begun.attemptId(),
                            "The supplier reported success without a reference; outcome requires review"));
                }
                return requiresNewTransaction.execute(status -> markSucceeded(fulfillmentId, begun.attemptId(),
                        result));
            }

            boolean lastAttempt = remaining == 1;
            Fulfillment fulfillment = requiresNewTransaction.execute(status -> markAttemptFailed(fulfillmentId,
                    begun.attemptId(), result, lastAttempt));
            if (fulfillment.getStatus() == FulfillmentStatus.FAILED) {
                return fulfillment;
            }
        }
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        Order order = requireOrder(fulfillment.getOrderId());
        return markFailed(order, fulfillment);
    }

    private String preflightFailure(SupplierTopupRequest request) {
        try {
            Optional<PlayerVerifyResponse> result = playerVerification.revalidateForFulfillment(
                    request.packageId(), request.playerId(), request.zoneId());
            if (result.isEmpty() || result.get().status() == PlayerVerificationStatus.VERIFIED) {
                return null;
            }
            if (result.get().status() == PlayerVerificationStatus.INVALID) {
                return "Player account verification failed; delivery requires review";
            }
        } catch (RuntimeException exception) {
            // Provider messages may contain credentials or personal data. Persist only a safe reason.
        }
        return "Player account verification is unavailable; delivery requires review";
    }

    private Fulfillment markPreflightFailed(UUID fulfillmentId, String message) {
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        Order order = requireOrder(fulfillment.getOrderId());
        fulfillment.setLastError(message);
        return markFailed(order, fulfillment);
    }

    private BeginAttempt beginAttempt(UUID fulfillmentId) {
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        int attemptNumber = fulfillment.getAttemptCount() + 1;
        fulfillment.setAttemptCount(attemptNumber);
        fulfillmentRepository.save(fulfillment);

        FulfillmentAttempt attempt = new FulfillmentAttempt();
        attempt.setFulfillmentId(fulfillmentId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setStatus(FulfillmentAttempt.Status.PENDING);
        attempt = attemptRepository.save(attempt);
        return new BeginAttempt(attempt.getId(), attemptNumber);
    }

    private Fulfillment markSucceeded(UUID fulfillmentId, UUID attemptId, SupplierTopupResponse result) {
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        Order order = requireOrder(fulfillment.getOrderId());
        FulfillmentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> attemptNotFound(attemptId));

        String reference = truncate(result.supplierReference(), MAX_REFERENCE_LENGTH);
        String message = messageOrDefault(result.message(), "Top-up delivered");
        attempt.setStatus(FulfillmentAttempt.Status.SUCCESS);
        attempt.setSupplierReference(reference);
        attempt.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        attemptRepository.save(attempt);

        fulfillment.setStatus(FulfillmentStatus.SUCCEEDED);
        fulfillment.setSupplierReference(reference);
        fulfillment.setLastError(null);
        fulfillment.setCompletedAt(Instant.now());
        fulfillment = fulfillmentRepository.save(fulfillment);
        transitionOrder(order, OrderStatus.COMPLETED, "Fulfillment completed (" + reference + ")");
        return fulfillment;
    }

    private Fulfillment markAttemptFailed(UUID fulfillmentId, UUID attemptId,
            SupplierTopupResponse result, boolean lastAttempt) {
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        FulfillmentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> attemptNotFound(attemptId));
        String message = result == null
                ? "The supplier returned no result"
                : messageOrDefault(result.message(), "The supplier rejected the delivery");

        attempt.setStatus(FulfillmentAttempt.Status.FAILED);
        attempt.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        attemptRepository.save(attempt);
        fulfillment.setLastError(truncate(message, MAX_MESSAGE_LENGTH));
        fulfillmentRepository.save(fulfillment);

        if (lastAttempt) {
            Order order = requireOrder(fulfillment.getOrderId());
            return markFailed(order, fulfillment);
        }
        return fulfillment;
    }

    private Fulfillment markUnknown(UUID fulfillmentId, UUID attemptId, String message) {
        Fulfillment fulfillment = fulfillmentRepository.findById(fulfillmentId)
                .orElseThrow(() -> fulfillmentNotFound(fulfillmentId));
        Order order = requireOrder(fulfillment.getOrderId());
        FulfillmentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> attemptNotFound(attemptId));

        String reason = truncate(message, MAX_MESSAGE_LENGTH);
        attempt.setMessage(reason);
        attemptRepository.save(attempt);
        fulfillment.setStatus(FulfillmentStatus.PROCESSING);
        fulfillment.setLastError(reason);
        fulfillmentRepository.save(fulfillment);
        transitionOrder(order, OrderStatus.NEEDS_REVIEW, reason);
        return fulfillment;
    }

    private Fulfillment markFailed(Order order, Fulfillment fulfillment) {
        fulfillment.setStatus(FulfillmentStatus.FAILED);
        fulfillment.setCompletedAt(Instant.now());
        fulfillment = fulfillmentRepository.save(fulfillment);
        transitionOrder(order, OrderStatus.NEEDS_REVIEW,
                "Supplier fulfillment failed after " + fulfillment.getAttemptCount()
                        + " attempt(s): " + fulfillment.getLastError());
        return fulfillment;
    }

    private boolean storedSelectionIsEligible(Fulfillment fulfillment) {
        if (fulfillment.getSupplierProductId() == null || fulfillment.getSupplierId() == null) {
            return true;
        }
        SupplierProduct product = supplierProducts.findById(fulfillment.getSupplierProductId()).orElse(null);
        if (product == null || !product.isActive()) {
            return false;
        }
        Supplier supplier = suppliers.findById(fulfillment.getSupplierId()).orElse(null);
        return supplier != null && supplier.getStatus() == SupplierStatus.ACTIVE;
    }

    private void requireFulfillable(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            throw conflict("ORDER_NOT_FULFILLABLE", "The order is not paid and cannot be fulfilled");
        }
    }

    /**
     * A persisted PENDING fulfillment whose order is already PROCESSING means the previous
     * run committed its claim and stopped before calling the supplier, so it may resume.
     */
    private void requirePendingResumable(Order order) {
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PROCESSING) {
            throw conflict("ORDER_NOT_FULFILLABLE", "The order is not paid and cannot be fulfilled");
        }
    }

    private void requireRetryable(Order order) {
        if (order.getStatus() != OrderStatus.PAID
                && order.getStatus() != OrderStatus.PROCESSING
                && order.getStatus() != OrderStatus.NEEDS_REVIEW) {
            throw conflict("ORDER_NOT_FULFILLABLE", "The order is not in a state that can be fulfilled");
        }
    }

    private SupplierTopupRequest requestFor(Order order, Fulfillment fulfillment) {
        return new SupplierTopupRequest(
                order.getId(),
                order.getOrderNumber(),
                order.getPackageId(),
                order.getGameNameSnapshot(),
                order.getPackageNameSnapshot(),
                order.getPlayerId(),
                order.getZoneId(),
                order.getQuantity(),
                fulfillment.getSupplierProductId(),
                fulfillment.getSupplierSku());
    }

    private ApiException attemptNotFound(UUID attemptId) {
        return new ApiException(HttpStatus.NOT_FOUND, "FULFILLMENT_ATTEMPT_NOT_FOUND",
                "Fulfillment attempt " + attemptId + " was not found");
    }

    private Claim stop(Fulfillment fulfillment) {
        return new Claim(fulfillment, null, false);
    }

    private record Claim(Fulfillment fulfillment, SupplierTopupRequest request, boolean execute) {
    }

    private record BeginAttempt(UUID attemptId, int attemptNumber) {
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
        // Lock the order even before a fulfillment exists, so initial delivery
        // and manual retries serialize across callbacks and application instances.
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "Order " + orderId + " was not found"));
    }

    private String messageOrDefault(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message.trim();
    }

    /** Keeps the machine-readable code so operators can triage the stored reason. */
    private String describe(ApiException exception) {
        return exception.getCode() + ": " + exception.getMessage();
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
