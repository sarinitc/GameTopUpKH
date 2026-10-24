package org.example.gametopupkh.payment;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.FulfillmentService;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentIntent;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final String EVENT_CREATE = "CREATE";
    private static final String EVENT_CREATE_RESERVED = "CREATE_RESERVED";
    private static final String EVENT_CREATE_ERROR = "CREATE_ERROR";
    private static final String EVENT_CALLBACK = "CALLBACK";
    private static final String EVENT_CALLBACK_ERROR = "CALLBACK_ERROR";
    private static final String EVENT_CALLBACK_THROTTLED = "CALLBACK_THROTTLED";
    private static final String EVENT_RECONCILE = "RECONCILE";
    private static final String EVENT_RECONCILE_ERROR = "RECONCILE_ERROR";
    private static final String EVENT_RECONCILE_THROTTLED = "RECONCILE_THROTTLED";
    private static final String EVENT_RECONCILIATION = "RECONCILIATION";
    private static final String EVENT_RECONCILIATION_SKIPPED = "RECONCILIATION_SKIPPED";
    private static final String EVENT_RECONCILIATION_ERROR = "RECONCILIATION_ERROR";
    private static final String EVENT_MOCK_SUCCESS = "MOCK_SUCCESS";
    private static final String ADMIN_ROLE = "ADMIN";

    private static final String REFERENCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int REFERENCE_LENGTH = 20;
    private static final int REFERENCE_ATTEMPTS = 5;

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final UserRepository userRepository;
    private final PaymentGateway gateway;
    private final FulfillmentService fulfillmentService;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate joiningTransaction;
    private final Duration reconciliationCooldown;
    private final String callbackUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            UserRepository userRepository,
            PaymentGateway gateway,
            FulfillmentService fulfillmentService,
            PlatformTransactionManager transactionManager,
            @Value("${payment.reconcile.cooldown:PT30S}") Duration reconciliationCooldown,
            @Value("${payment.aba.callback-url:http://localhost:8082/api/v1/payments/aba/callback}")
            String callbackUrl) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.userRepository = userRepository;
        this.gateway = gateway;
        this.fulfillmentService = fulfillmentService;
        this.requiresNewTransaction = transactionTemplate(transactionManager,
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.joiningTransaction = transactionTemplate(transactionManager,
                TransactionDefinition.PROPAGATION_REQUIRED);
        this.reconciliationCooldown = reconciliationCooldown;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public CreatePaymentResponse createAbaPayment(UUID orderId, UUID userId) {
        User customer = requireActiveUser(userId);
        Order order = orderRepository.findWithUserById(orderId)
                .orElseThrow(() -> orderNotFound(orderId));
        if (order.getUser() == null || !order.getUser().getId().equals(customer.getId())) {
            throw orderNotFound(orderId);
        }

        PaymentProvider provider = providerOf(gateway.name());
        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            if (existing.isPresent() && existing.get().getStatus().isSuccessful()) {
                fulfillCompletedPayment(existing.get());
                return CreatePaymentResponse.from(existing.get());
            }
            throw conflict("PAYMENT_NOT_PAYABLE",
                    "The order is not waiting for payment");
        }
        if (existing.isPresent()) {
            return resume(existing.get(), provider);
        }

        Reservation reservation;
        try {
            reservation = reserve(order, customer, provider);
        } catch (DataIntegrityViolationException e) {
            Payment concurrent = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> conflict("PAYMENT_IN_PROGRESS",
                            "A payment for this order is already being initialized"));
            return resume(concurrent, provider);
        }
        if (!reservation.created()) {
            return resume(reservation.payment(), provider);
        }

        Payment reserved = reservation.payment();
        try {
            PaymentIntent intent = gateway.createPayment(new PaymentRequest(
                    reserved.getReference(),
                    reserved.getAmount(),
                    reserved.getCurrency(),
                    order.getPlayerId(),
                    callbackUrl,
                    "Order " + order.getOrderNumber()));
            Payment applied = applyProviderIntent(reserved.getId(), intent, EVENT_CREATE, customer.getId(), false, null);
            return CreatePaymentResponse.from(applied);
        } catch (PaymentGatewayException e) {
            markCreationUncertain(reserved.getId(), customer.getId());
            throw providerUnavailable();
        } catch (RuntimeException e) {
            markCreationUncertain(reserved.getId(), customer.getId());
            throw providerUnavailable();
        }
    }

    @Override
    public PaymentStatusResponse getPayment(UUID paymentId, UUID requesterId, boolean admin) {
        Payment payment = requirePayment(paymentId);
        if (!admin) {
            Order order = orderRepository.findWithUserById(payment.getOrderId())
                    .orElseThrow(() -> paymentNotFound(paymentId));
            if (order.getUser() == null || !order.getUser().getId().equals(requesterId)) {
                throw paymentNotFound(paymentId);
            }
        }
        return PaymentStatusResponse.from(payment);
    }

    @Override
    public PaymentStatusResponse confirmMockPayment(String identifier) {
        if (providerOf(gateway.name()) != PaymentProvider.MOCK) {
            throw conflict("MOCK_PAYMENT_UNAVAILABLE",
                    "Mock payment confirmation is only available with the mock gateway");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_IDENTIFIER_REQUIRED",
                    "A payment id or reference is required");
        }
        Payment payment = findPaymentByIdentifier(identifier.trim());
        requireMatchingProvider(payment, PaymentProvider.MOCK);

        if (payment.getStatus().isFinal()) {
            fulfillCompletedPayment(payment);
            return PaymentStatusResponse.from(payment);
        }

        PaymentIntent intent = mockCompletion(payment);
        Payment updated = applyProviderIntent(payment.getId(), intent, EVENT_MOCK_SUCCESS, null, false,
                "mock-success:" + payment.getId());
        return PaymentStatusResponse.from(updated);
    }

    @Override
    public void processCallback(String reference, String eventKey) {
        if (reference == null || reference.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_REFERENCE_REQUIRED",
                    "A payment reference is required");
        }
        if (eventKey == null || eventKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALLBACK_EVENT_KEY_REQUIRED",
                    "A callback event key is required");
        }
        Payment payment = findByReference(reference);
        PaymentProvider provider = providerOf(gateway.name());
        requireMatchingProvider(payment, provider);

        String normalizedKey = truncate(eventKey.trim(), 255);
        if (paymentEventRepository.existsByPaymentIdAndEventKey(payment.getId(), normalizedKey)) {
            fulfillCompletedPayment(payment);
            return;
        }

        if (payment.getStatus().isFinal()) {
            recordEvent(payment.getId(), EVENT_CALLBACK, normalizedKey,
                    "Provider callback for an already final payment", null);
            fulfillCompletedPayment(payment);
            return;
        }

        if (isThrottled(payment)) {
            recordEvent(payment.getId(), EVENT_CALLBACK_THROTTLED, null,
                    "Provider callback status check skipped by the per-payment cooldown", null);
            return;
        }

        PaymentIntent intent;
        try {
            intent = providerIntent(payment, provider);
        } catch (PaymentGatewayException e) {
            recordEvent(payment.getId(), EVENT_CALLBACK_ERROR, null,
                    "Provider status check failed; callback can be retried", null);
            throw providerUnavailable();
        }
        applyProviderIntent(payment.getId(), intent, EVENT_CALLBACK, null, true, normalizedKey);
    }

    @Override
    public PaymentStatusResponse reconcile(UUID paymentId, UUID adminId) {
        User admin = requireActiveAdmin(adminId);
        return reconcileInternal(paymentId, admin.getId(),
                EVENT_RECONCILE, EVENT_RECONCILE_THROTTLED, EVENT_RECONCILE_ERROR);
    }

    @Override
    public PaymentStatusResponse reconcileUnattended(UUID paymentId) {
        return reconcileInternal(paymentId, null,
                EVENT_RECONCILIATION, EVENT_RECONCILIATION_SKIPPED, EVENT_RECONCILIATION_ERROR);
    }

    private PaymentStatusResponse reconcileInternal(UUID paymentId, UUID actorUserId,
            String eventType, String throttledEventType, String errorEventType) {
        Payment payment = requirePayment(paymentId);
        PaymentProvider provider = providerOf(gateway.name());
        requireMatchingProvider(payment, provider);

        if (payment.getStatus().isFinal()) {
            recordEvent(payment.getId(), eventType, null,
                    "Reconciliation skipped because the payment is already final", actorUserId);
            fulfillCompletedPayment(payment);
            return PaymentStatusResponse.from(payment);
        }

        if (isThrottled(payment)) {
            recordEvent(payment.getId(), throttledEventType, null,
                    "Reconciliation skipped by the per-payment cooldown", actorUserId);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PAYMENT_RECONCILIATION_THROTTLED",
                    "This payment was checked recently; retry after the cooldown");
        }

        PaymentIntent intent;
        try {
            intent = providerIntent(payment, provider);
        } catch (PaymentGatewayException e) {
            recordEvent(payment.getId(), errorEventType, null,
                    "Provider status check failed during reconciliation", actorUserId);
            throw providerUnavailable();
        }
        Payment updated = applyProviderIntent(payment.getId(), intent, eventType, actorUserId, true, null);
        return PaymentStatusResponse.from(updated);
    }

    private CreatePaymentResponse resume(Payment payment, PaymentProvider provider) {
        requireMatchingProvider(payment, provider);
        fulfillCompletedPayment(payment);
        return switch (payment.getStatus()) {
            case INITIALIZING -> throw conflict("PAYMENT_IN_PROGRESS",
                    "A payment for this order is already being initialized");
            case NEEDS_REVIEW -> throw conflict("PAYMENT_REQUIRES_REVIEW",
                    "The existing payment needs manual review before another attempt");
            case FAILED, EXPIRED, CANCELLED -> throw conflict("PAYMENT_ALREADY_FINISHED",
                    "The existing payment is closed; create a new order to pay again");
            default -> CreatePaymentResponse.from(payment);
        };
    }

    private Reservation reserve(Order order, User customer, PaymentProvider provider) {
        return inWriteTransaction(() -> paymentRepository.findForUpdateByOrderId(order.getId())
                .map(existing -> new Reservation(existing, false))
                .orElseGet(() -> {
                    Payment payment = new Payment();
                    payment.setOrderId(order.getId());
                    payment.setProvider(provider);
                    payment.setReference(generateReference());
                    payment.setStatus(PaymentStatus.INITIALIZING);
                    payment.setAmount(order.getTotalAmount());
                    payment.setCurrency(order.getCurrency());
                    Payment saved = paymentRepository.save(payment);
                    recordEvent(saved.getId(), EVENT_CREATE_RESERVED, null,
                            "Local payment reference reserved before the provider call", customer.getId());
                    return new Reservation(saved, true);
                }));
    }

    private Payment applyProviderIntent(UUID paymentId, PaymentIntent intent, String eventType,
            UUID actorUserId, boolean providerCheck, String eventKey) {
        Payment applied = inWriteTransaction(() -> {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> paymentNotFound(paymentId));
            if (eventKey != null
                    && paymentEventRepository.existsByPaymentIdAndEventKey(paymentId, eventKey)) {
                return payment;
            }
            Instant now = Instant.now();

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                if (providerCheck) {
                    payment.setLastProviderCheckAt(now);
                    payment = paymentRepository.save(payment);
                }
                recordEvent(payment.getId(), eventType, eventKey,
                        "Provider answer ignored because the payment is already completed", actorUserId);
                return payment;
            }
            if (payment.getStatus() == PaymentStatus.FAILED
                    || payment.getStatus() == PaymentStatus.EXPIRED
                    || payment.getStatus() == PaymentStatus.CANCELLED) {
                if (providerCheck) {
                    payment.setLastProviderCheckAt(now);
                    payment = paymentRepository.save(payment);
                }
                recordEvent(payment.getId(), eventType, eventKey,
                        "Provider answer ignored because the payment is already closed", actorUserId);
                return payment;
            }

            if (intent == null || intent.status() == null) {
                recordEvent(payment.getId(), eventType, eventKey,
                        "Provider returned no usable status", actorUserId);
                return payment;
            }

            if (intent.gatewayReference() != null && !intent.gatewayReference().isBlank()) {
                payment.setGatewayReference(intent.gatewayReference());
            }
            if (intent.qrString() != null && !intent.qrString().isBlank()) {
                payment.setQrString(intent.qrString());
            }
            if (intent.qrImageUrl() != null && !intent.qrImageUrl().isBlank()) {
                payment.setQrImageUrl(intent.qrImageUrl());
            }
            if (intent.redirectUrl() != null && !intent.redirectUrl().isBlank()) {
                payment.setRedirectUrl(intent.redirectUrl());
            }
            if (intent.expiresAt() != null) {
                payment.setExpiresAt(databasePrecision(intent.expiresAt()));
            }

            PaymentStatus target = intent.status();
            if (target == PaymentStatus.COMPLETED) {
                target = applyCompletion(payment, intent, now);
            } else if (target == PaymentStatus.PENDING && payment.getStatus() == PaymentStatus.NEEDS_REVIEW) {
                target = PaymentStatus.NEEDS_REVIEW;
            }

            payment.setStatus(target);
            if (providerCheck) {
                payment.setLastProviderCheckAt(now);
            }
            Payment saved = paymentRepository.save(payment);
            recordEvent(saved.getId(), eventType, eventKey,
                    "Provider reported " + target.name(), actorUserId);
            return saved;
        });
        fulfillCompletedPayment(applied);
        return applied;
    }

    private void fulfillCompletedPayment(Payment payment) {
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            return;
        }
        UUID orderId = payment.getOrderId();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    startFulfillment(orderId);
                }
            });
            return;
        }
        startFulfillment(orderId);
    }

    private void startFulfillment(UUID orderId) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> fulfillmentService.fulfillOrder(orderId));
        } catch (RuntimeException e) {
            log.warn("Could not start fulfillment for order {}", orderId, e);
        }
    }

    private PaymentStatus applyCompletion(Payment payment, PaymentIntent intent, Instant now) {
        if (!matchesExpectedAmount(payment, intent)) {
            return PaymentStatus.NEEDS_REVIEW;
        }
        Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
        if (order == null) {
            return PaymentStatus.NEEDS_REVIEW;
        }
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            order.setStatus(OrderStatus.PAID);
            orderRepository.save(order);
            OrderStatusHistory history = new OrderStatusHistory();
            history.setOrderId(order.getId());
            history.setStatus(OrderStatus.PAID);
            history.setNote("Payment " + payment.getReference() + " completed");
            orderStatusHistoryRepository.save(history);
            payment.setPaidAt(now);
            return PaymentStatus.COMPLETED;
        }
        if (order.getStatus() == OrderStatus.PAID) {
            if (payment.getPaidAt() == null) {
                payment.setPaidAt(now);
            }
            return PaymentStatus.COMPLETED;
        }
        return PaymentStatus.NEEDS_REVIEW;
    }

    private boolean matchesExpectedAmount(Payment payment, PaymentIntent intent) {
        if (intent.amount() == null || payment.getAmount() == null) {
            return false;
        }
        if (payment.getAmount().compareTo(intent.amount()) != 0) {
            return false;
        }
        if (intent.currency() == null || payment.getCurrency() == null) {
            return false;
        }
        return payment.getCurrency().equalsIgnoreCase(intent.currency());
    }

    private void markCreationUncertain(UUID paymentId, UUID actorUserId) {
        inWriteTransaction(() -> {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> paymentNotFound(paymentId));
            if (payment.getStatus() == PaymentStatus.INITIALIZING) {
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                paymentRepository.save(payment);
            }
            recordEvent(payment.getId(), EVENT_CREATE_ERROR, null,
                    "Provider creation did not complete; the payment is reserved for review", actorUserId);
            return payment;
        });
    }

    private User requireActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Authentication is required to access this resource"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                    "The account is " + user.getAccountStatus().name().toLowerCase(Locale.ROOT));
        }
        return user;
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
                    "The authenticated user is not allowed to reconcile payments");
        }
        return user;
    }

    private Payment requirePayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> paymentNotFound(paymentId));
    }

    private Payment findByReference(String reference) {
        return paymentRepository.findByReference(reference)
                .or(() -> paymentRepository.findByGatewayReference(reference))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                        "Payment " + reference + " was not found"));
    }

    private Payment findPaymentByIdentifier(String identifier) {
        try {
            return requirePayment(UUID.fromString(identifier));
        } catch (IllegalArgumentException e) {
            return findByReference(identifier);
        }
    }

    private void requireMatchingProvider(Payment payment, PaymentProvider provider) {
        if (payment.getProvider() != provider) {
            throw conflict("PAYMENT_PROVIDER_MISMATCH",
                    "The payment was initialized with a different provider");
        }
    }

    private PaymentProvider providerOf(String gatewayName) {
        if (gatewayName == null) {
            throw conflict("PAYMENT_PROVIDER_UNSUPPORTED", "No payment provider is configured");
        }
        try {
            return PaymentProvider.valueOf(gatewayName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw conflict("PAYMENT_PROVIDER_UNSUPPORTED",
                    "The configured payment provider is not supported");
        }
    }

    private boolean isThrottled(Payment payment) {
        Instant lastCheck = payment.getLastProviderCheckAt();
        return lastCheck != null && lastCheck.plus(reconciliationCooldown).isAfter(Instant.now());
    }

    private String gatewayReferenceOf(Payment payment) {
        return payment.getGatewayReference() == null || payment.getGatewayReference().isBlank()
                ? payment.getReference()
                : payment.getGatewayReference();
    }

    private PaymentIntent providerIntent(Payment payment, PaymentProvider provider) {
        if (provider == PaymentProvider.MOCK) {
            return mockCompletion(payment);
        }
        return gateway.checkStatus(gatewayReferenceOf(payment));
    }

    private PaymentIntent mockCompletion(Payment payment) {
        return new PaymentIntent(
                gateway.name(),
                gatewayReferenceOf(payment),
                PaymentStatus.COMPLETED,
                payment.getAmount(),
                payment.getCurrency(),
                payment.getRedirectUrl(),
                payment.getCreatedAt(),
                payment.getQrString(),
                payment.getQrImageUrl(),
                payment.getExpiresAt());
    }

    private String generateReference() {
        for (int attempt = 0; attempt < REFERENCE_ATTEMPTS; attempt++) {
            StringBuilder value = new StringBuilder(REFERENCE_LENGTH);
            for (int i = 0; i < REFERENCE_LENGTH; i++) {
                value.append(REFERENCE_ALPHABET.charAt(secureRandom.nextInt(REFERENCE_ALPHABET.length())));
            }
            String candidate = value.toString();
            if (!paymentRepository.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_REFERENCE_UNAVAILABLE",
                "Could not allocate a payment reference, please retry");
    }

    private void recordEvent(UUID paymentId, String type, String eventKey, String note, UUID actorUserId) {
        try {
            inWriteTransaction(() -> {
                if (eventKey != null && paymentEventRepository.existsByPaymentIdAndEventKey(paymentId, eventKey)) {
                    return null;
                }
                PaymentEvent event = new PaymentEvent();
                event.setPaymentId(paymentId);
                event.setType(type);
                event.setEventKey(eventKey == null ? null : truncate(eventKey, 255));
                event.setNote(truncate(note, 255));
                event.setActorUserId(actorUserId);
                return paymentEventRepository.save(event);
            });
        } catch (DataIntegrityViolationException e) {
            // A concurrent callback already recorded this idempotency key.
        }
    }

    private <T> T inWriteTransaction(Supplier<T> work) {
        TransactionTemplate template = TransactionSynchronizationManager.isActualTransactionActive()
                ? joiningTransaction
                : requiresNewTransaction;
        return template.execute(status -> work.get());
    }

    private TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager, int propagation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        return template;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Instant databasePrecision(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private ApiException orderNotFound(UUID orderId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                "Order " + orderId + " was not found");
    }

    private ApiException paymentNotFound(UUID paymentId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                "Payment " + paymentId + " was not found");
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException providerUnavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_UNAVAILABLE",
                "The payment provider could not be reached");
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<PaymentStatusResponse> findAll(PaymentStatus status) {
        java.util.List<Payment> payments = status == null
                ? paymentRepository.findAllByOrderByCreatedAtDesc()
                : paymentRepository.findAllByStatusOrderByCreatedAtDesc(status);
        return payments.stream().map(PaymentStatusResponse::from).toList();
    }

    private record Reservation(Payment payment, boolean created) {
    }
}
