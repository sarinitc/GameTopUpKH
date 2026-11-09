package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.fulfillment.FulfillmentService;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentEvent;
import org.example.gametopupkh.payment.PaymentEventRepository;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongAccountVerificationResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckAccountResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse;
import org.example.gametopupkh.payment.bakong.dto.BakongCheckTransactionResponse.BakongTransactionData;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
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

/**
 * Authoritative verification service for Bakong KHQR transactions.
 * Queries the Bakong Open API, performs cryptographic and monetary integrity checks,
 * atomically updates payment and order status, and triggers downstream fulfillment.
 */
@Service
public class BakongVerificationService {

    private static final Logger log = LoggerFactory.getLogger(BakongVerificationService.class);

    public static final String EVENT_VERIFIED = "PAYMENT_VERIFIED";
    public static final String EVENT_VERIFY_PENDING = "VERIFY_PENDING";
    public static final String EVENT_VERIFY_ERROR = "VERIFY_ERROR";
    public static final String EVENT_VERIFY_THROTTLED = "VERIFY_THROTTLED";
    public static final String EVENT_EXPIRED = "PAYMENT_EXPIRED";
    public static final String EVENT_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String EVENT_CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String EVENT_RECIPIENT_MISMATCH = "RECIPIENT_MISMATCH";
    public static final String EVENT_REPLAY_ATTEMPT = "REPLAY_ATTEMPT";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final FulfillmentService fulfillmentService;
    private final BakongApiClient apiClient;
    private final BakongProperties properties;
    private final Duration cooldown;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate joiningTransaction;

    public BakongVerificationService(
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            FulfillmentService fulfillmentService,
            BakongApiClient apiClient,
            BakongProperties properties,
            PlatformTransactionManager transactionManager,
            @Value("${payment.reconcile.cooldown:PT30S}") Duration cooldown) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.fulfillmentService = fulfillmentService;
        this.apiClient = apiClient;
        this.properties = properties;
        this.cooldown = cooldown;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.joiningTransaction = new TransactionTemplate(transactionManager);
        this.joiningTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Verifies the configured merchant account with Bakong's
     * {@code /v1/check_bakong_account} endpoint. This is a read-only diagnostic used
     * by staff before enabling live payments; it never moves money.
     *
     * @return the provider's verification result for the configured account
     */
    public BakongAccountVerificationResponse verifyMerchantAccount() {
        String accountId = properties.accountId();
        if (accountId == null || accountId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAKONG_ACCOUNT_ID_MISSING",
                    "BAKONG_ACCOUNT_ID must be configured before verifying the merchant account");
        }

        BakongCheckAccountResponse response;
        try {
            response = apiClient.checkAccount(accountId);
        } catch (PaymentGatewayException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_UNAVAILABLE",
                    "The Bakong payment provider could not be reached: " + e.getMessage(), e);
        }

        BakongCheckAccountResponse.BakongAccountData data = response == null ? null : response.data();
        return new BakongAccountVerificationResponse(
                accountId,
                response != null && response.isVerified(),
                response == null ? null : response.responseCode(),
                response == null ? null : response.responseMessage(),
                data == null ? null : data.accountStatus(),
                data == null ? null : data.frozen(),
                data == null ? null : data.kycStatus(),
                data == null ? null : data.canReceive());
    }

    /**
     * Verifies a Bakong KHQR payment by payment ID.
     */
    public PaymentStatusResponse verifyPayment(UUID paymentId) {
        return verifyPayment(paymentId, null);
    }

    /**
     * Verifies a Bakong KHQR payment with an optional authenticated actor.
     */
    public PaymentStatusResponse verifyPayment(UUID paymentId, UUID actorUserId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                        "Payment " + paymentId + " was not found"));

        if (payment.getProvider() != PaymentProvider.BAKONG) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_PROVIDER_MISMATCH",
                    "The payment was initialized with a different provider: " + payment.getProvider());
        }

        // Idempotent return for already final payments
        if (payment.getStatus().isFinal()) {
            return PaymentStatusResponse.from(payment);
        }

        // Rate limit / per-payment cooldown check
        if (isThrottled(payment)) {
            recordEvent(payment.getId(), EVENT_VERIFY_THROTTLED, null,
                    "Verification skipped by per-payment cooldown", actorUserId);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PAYMENT_RECONCILIATION_THROTTLED",
                    "This payment was checked recently; retry after the cooldown");
        }

        Instant now = Instant.now();

        // If payment has already expired, perform one final check before closing
        boolean isExpired = payment.getExpiresAt() != null && payment.getExpiresAt().isBefore(now);

        String md5 = payment.getQrMd5();
        if (md5 == null || md5.isBlank()) {
            recordEvent(payment.getId(), EVENT_VERIFY_ERROR, null,
                    "Payment does not have a recorded QR MD5 hash", actorUserId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_MD5_MISSING",
                    "Payment does not have a valid QR MD5 hash");
        }

        BakongCheckTransactionResponse response;
        try {
            response = apiClient.checkTransaction(md5);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            inWriteTransaction(() -> {
                Payment p = paymentRepository.findByIdForUpdate(paymentId).orElse(payment);
                p.setLastProviderCheckAt(now);
                paymentRepository.save(p);
                recordEvent(paymentId, EVENT_VERIFY_ERROR, null,
                        "Bakong API check failed: " + e.getMessage(), actorUserId);
                return p;
            });
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_UNAVAILABLE",
                    "The Bakong payment provider could not be reached: " + e.getMessage(), e);
        }

        if (response == null || !response.isSuccess()) {
            // Transaction is unpaid or not found yet
            return inWriteTransaction(() -> {
                Payment p = paymentRepository.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));

                p.setLastProviderCheckAt(now);
                p.setVerificationAttempts(p.getVerificationAttempts() + 1);

                if (isExpired && p.getStatus() == PaymentStatus.PENDING) {
                    p.setStatus(PaymentStatus.EXPIRED);
                    Payment saved = paymentRepository.save(p);
                    Order order = orderRepository.findById(p.getOrderId()).orElse(null);
                    if (order != null && order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                        order.setStatus(OrderStatus.CANCELLED);
                        orderRepository.save(order);
                        recordOrderStatus(order.getId(), OrderStatus.CANCELLED,
                                "Payment expired without Bakong confirmation");
                    }
                    recordEvent(p.getId(), EVENT_EXPIRED, null,
                            "Bakong payment expired without successful transaction", actorUserId);
                    return PaymentStatusResponse.from(saved);
                }

                Payment saved = paymentRepository.save(p);
                recordEvent(p.getId(), EVENT_VERIFY_PENDING, null,
                        "Bakong transaction not completed yet (responseCode: "
                                + (response != null ? response.responseCode() : "null") + ")", actorUserId);
                return PaymentStatusResponse.from(saved);
            });
        }

        // Transaction is confirmed paid by Bakong
        BakongTransactionData data = response.data();
        return processSuccessfulTransaction(paymentId, data, actorUserId, now);
    }

    private PaymentStatusResponse processSuccessfulTransaction(
            UUID paymentId,
            BakongTransactionData data,
            UUID actorUserId,
            Instant now) {
        return inWriteTransaction(() -> {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));

            // Another request (or a concurrent verification) already completed
            // this payment. Fulfillment is claimed durably by FulfillmentService,
            // so do not submit it a second time from here.
            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                return PaymentStatusResponse.from(payment);
            }

            String transactionHash = data.hash();
            if (transactionHash == null || transactionHash.isBlank()) {
                recordEvent(payment.getId(), EVENT_VERIFY_ERROR, null,
                        "Bakong reported success but returned empty transaction hash", actorUserId);
                payment.setLastProviderCheckAt(now);
                payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                Payment saved = paymentRepository.save(payment);
                markOrderNeedsReview(payment.getOrderId(), "Bakong returned empty transaction hash");
                return PaymentStatusResponse.from(saved);
            }

            // 1. Transaction Replay Check
            Optional<Payment> existingWithHash = paymentRepository.findByGatewayReference(transactionHash)
                    .or(() -> paymentRepository.findByBakongHash(transactionHash));
            if (existingWithHash.isPresent() && !existingWithHash.get().getId().equals(payment.getId())) {
                log.warn("Security Alert: Replay attempt detected. Hash {} already belongs to payment {}",
                        transactionHash, existingWithHash.get().getId());
                recordEvent(payment.getId(), EVENT_REPLAY_ATTEMPT, transactionHash,
                        "Transaction hash already used by another payment: " + existingWithHash.get().getId(), actorUserId);
                payment.setLastProviderCheckAt(now);
                payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                Payment saved = paymentRepository.save(payment);
                markOrderNeedsReview(payment.getOrderId(), "Duplicate Bakong transaction hash detected");
                return PaymentStatusResponse.from(saved);
            }

            // 2. Amount Validation
            if (data.amount() == null || data.amount().compareTo(payment.getAmount()) != 0) {
                log.warn("Amount mismatch for payment {}: expected {}, received {}",
                        payment.getId(), payment.getAmount(), data.amount());
                recordEvent(payment.getId(), EVENT_AMOUNT_MISMATCH, transactionHash,
                        "Expected amount " + payment.getAmount() + " but Bakong returned " + data.amount(), actorUserId);
                payment.setLastProviderCheckAt(now);
                payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                Payment saved = paymentRepository.save(payment);
                markOrderNeedsReview(payment.getOrderId(), "Bakong payment amount mismatch");
                return PaymentStatusResponse.from(saved);
            }

            // 3. Currency Validation (accept ISO codes and Bakong numeric codes)
            String actualCurrency = normalizeCurrency(data.currency());
            if (actualCurrency == null || !actualCurrency.equalsIgnoreCase(payment.getCurrency())) {
                log.warn("Currency mismatch for payment {}: expected {}, received {}",
                        payment.getId(), payment.getCurrency(), data.currency());
                recordEvent(payment.getId(), EVENT_CURRENCY_MISMATCH, transactionHash,
                        "Expected currency " + payment.getCurrency() + " but Bakong returned " + data.currency(), actorUserId);
                payment.setLastProviderCheckAt(now);
                payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                Payment saved = paymentRepository.save(payment);
                markOrderNeedsReview(payment.getOrderId(), "Bakong payment currency mismatch");
                return PaymentStatusResponse.from(saved);
            }

            // 4. Recipient Account Validation (fail closed: an unknown recipient is never accepted)
            String expectedRecipient = properties.accountId();
            String actualRecipient = data.toAccountId();
            if (expectedRecipient == null || expectedRecipient.isBlank()
                    || actualRecipient == null || actualRecipient.isBlank()
                    || !actualRecipient.trim().equalsIgnoreCase(expectedRecipient.trim())) {
                log.warn("Recipient account mismatch for payment {}: expected {}, received {}",
                        payment.getId(), expectedRecipient, actualRecipient);
                recordEvent(payment.getId(), EVENT_RECIPIENT_MISMATCH, transactionHash,
                        "Expected recipient " + expectedRecipient + " but got " + actualRecipient, actorUserId);
                payment.setLastProviderCheckAt(now);
                payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                Payment saved = paymentRepository.save(payment);
                markOrderNeedsReview(payment.getOrderId(), "Bakong recipient account mismatch");
                return PaymentStatusResponse.from(saved);
            }

            // All validations passed - mark payment COMPLETED and order PAID
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setPaidAt(now);
            payment.setGatewayReference(transactionHash);
            payment.setBakongHash(transactionHash);
            payment.setLastProviderCheckAt(now);
            payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
            Payment saved = paymentRepository.save(payment);

            Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.PAID);
                orderRepository.save(order);
                recordOrderStatus(order.getId(), OrderStatus.PAID,
                        "Bakong KHQR payment " + payment.getReference() + " verified successfully (hash: " + transactionHash + ")");
            }

            recordEvent(saved.getId(), EVENT_VERIFIED, transactionHash,
                    "Bakong KHQR transaction verified with hash: " + transactionHash, actorUserId);

            fulfillCompletedPayment(saved);
            return PaymentStatusResponse.from(saved);
        });
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

    private void markOrderNeedsReview(UUID orderId, String note) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && order.getStatus() != OrderStatus.NEEDS_REVIEW) {
            order.setStatus(OrderStatus.NEEDS_REVIEW);
            orderRepository.save(order);
            recordOrderStatus(order.getId(), OrderStatus.NEEDS_REVIEW, note);
        }
    }

    private void recordOrderStatus(UUID orderId, OrderStatus status, String note) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrderId(orderId);
        history.setStatus(status);
        history.setNote(note);
        orderStatusHistoryRepository.save(history);
    }

    private void recordEvent(UUID paymentId, String type, String eventKey, String note, UUID actorUserId) {
        try {
            if (eventKey != null && paymentEventRepository.existsByPaymentIdAndEventKey(paymentId, eventKey)) {
                return;
            }
            PaymentEvent event = new PaymentEvent();
            event.setPaymentId(paymentId);
            event.setType(type);
            event.setEventKey(eventKey == null ? null : truncate(eventKey, 255));
            event.setNote(truncate(note, 255));
            event.setActorUserId(actorUserId);
            paymentEventRepository.save(event);
        } catch (DataIntegrityViolationException e) {
            // Concurrent event already recorded
        }
    }

    private boolean isThrottled(Payment payment) {
        Instant lastCheck = payment.getLastProviderCheckAt();
        return lastCheck != null && lastCheck.plus(cooldown).isAfter(Instant.now());
    }

    private <T> T inWriteTransaction(Supplier<T> work) {
        TransactionTemplate template = TransactionSynchronizationManager.isActualTransactionActive()
                ? joiningTransaction
                : requiresNewTransaction;
        return template.execute(status -> work.get());
    }

    /**
     * Bakong can report currency as an ISO code ("USD") or an EMVCo numeric code
     * ("840"). Normalize both forms before comparing with the order currency.
     */
    private static String normalizeCurrency(String currency) {
        if (currency == null) {
            return null;
        }
        String trimmed = currency.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "840", "USD" -> "USD";
            case "116", "KHR" -> "KHR";
            default -> trimmed;
        };
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
