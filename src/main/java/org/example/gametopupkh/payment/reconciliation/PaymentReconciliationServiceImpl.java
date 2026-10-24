package org.example.gametopupkh.payment.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.PaymentProvider;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentService;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.payment.dto.PaymentStatusResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.example.gametopupkh.payment.reconciliation.PaymentReconciliationResult.Outcome;
import org.example.gametopupkh.payment.reconciliation.PaymentReconciliationResult.PaymentOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * Uses the normal payment state machine for every candidate, so amount/currency
 * verification, order transitions, audit events and after-commit fulfillment stay
 * in one place. Provider calls happen before the short write transaction owned by
 * {@link PaymentService#reconcileUnattended(UUID)}.
 */
@Service
public class PaymentReconciliationServiceImpl implements PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final PaymentGateway gateway;
    private final Duration cooldown;
    private final Duration minAge;
    private final int batchSize;

    public PaymentReconciliationServiceImpl(
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            PaymentGateway gateway,
            @Value("${payment.reconcile.cooldown:PT30S}") Duration cooldown,
            @Value("${payment.reconciliation.min-age:PT1M}") Duration minAge,
            @Value("${payment.reconciliation.batch-size:50}") int batchSize) {
        Assert.isTrue(batchSize > 0, "payment.reconciliation.batch-size must be positive");
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.gateway = gateway;
        this.cooldown = cooldown;
        this.minAge = minAge;
        this.batchSize = batchSize;
    }

    @Override
    public PaymentReconciliationResult reconcile() {
        Instant startedAt = Instant.now();
        PaymentProvider provider = activeProvider();
        if (provider != PaymentProvider.ABA) {
            return summarize(startedAt, List.of());
        }

        List<UUID> paymentIds = paymentRepository.findReconciliationCandidates(
                provider,
                startedAt.minus(minAge),
                startedAt.minus(cooldown),
                PageRequest.of(0, batchSize));

        List<PaymentOutcome> outcomes = new ArrayList<>(paymentIds.size());
        for (UUID paymentId : paymentIds) {
            outcomes.add(reconcileOne(paymentId));
        }

        PaymentReconciliationResult result = summarize(startedAt, outcomes);
        if (result.inspected() > 0) {
            log.info("Payment reconciliation inspected={} completed={} pending={} needsReview={} "
                            + "closed={} skipped={} errors={}",
                    result.inspected(), result.completed(), result.pending(), result.needsReview(),
                    result.closed(), result.skipped(), result.errors());
        }
        return result;
    }

    private PaymentOutcome reconcileOne(UUID paymentId) {
        try {
            PaymentStatusResponse response = paymentService.reconcileUnattended(paymentId);
            PaymentStatus status = response.status();
            return new PaymentOutcome(paymentId, outcomeOf(status), status,
                    "Provider status " + status);
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                return new PaymentOutcome(paymentId, Outcome.SKIPPED, null, e.getCode());
            }
            log.warn("Payment reconciliation failed for {} ({})", paymentId, e.getCode());
            return new PaymentOutcome(paymentId, Outcome.ERROR, null, e.getCode());
        } catch (RuntimeException e) {
            log.warn("Payment reconciliation failed for {}", paymentId, e);
            return new PaymentOutcome(paymentId, Outcome.ERROR, null, e.getClass().getSimpleName());
        }
    }

    private Outcome outcomeOf(PaymentStatus status) {
        return switch (status) {
            case COMPLETED -> Outcome.COMPLETED;
            case PENDING, INITIALIZING -> Outcome.PENDING;
            case NEEDS_REVIEW -> Outcome.NEEDS_REVIEW;
            case FAILED, EXPIRED, CANCELLED -> Outcome.CLOSED;
        };
    }

    private PaymentProvider activeProvider() {
        String name = gateway.name();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return PaymentProvider.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PaymentReconciliationResult summarize(Instant startedAt, List<PaymentOutcome> outcomes) {
        return new PaymentReconciliationResult(
                startedAt,
                Instant.now(),
                outcomes.size(),
                count(outcomes, Outcome.COMPLETED),
                count(outcomes, Outcome.PENDING),
                count(outcomes, Outcome.NEEDS_REVIEW),
                count(outcomes, Outcome.CLOSED),
                count(outcomes, Outcome.SKIPPED),
                count(outcomes, Outcome.ERROR),
                outcomes);
    }

    private int count(List<PaymentOutcome> outcomes, Outcome target) {
        return (int) outcomes.stream().filter(outcome -> outcome.outcome() == target).count();
    }
}
