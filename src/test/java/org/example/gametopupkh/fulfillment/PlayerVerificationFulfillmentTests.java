package org.example.gametopupkh.fulfillment;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.order.OrderStatusHistory;
import org.example.gametopupkh.order.OrderStatusHistoryRepository;
import org.example.gametopupkh.player.dto.PlayerVerificationStatus;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.example.gametopupkh.player.service.PlayerVerificationService;
import org.example.gametopupkh.supplier.SupplierTestFixtures;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClientRegistry;
import org.example.gametopupkh.supplier.integration.SupplierTopupRequest;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:player-fulfillment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "fulfillment.worker.enabled=false", "supplier.max-attempts=2"
})
@ActiveProfiles("test")
class PlayerVerificationFulfillmentTests {

    @Autowired private FulfillmentService service;
    @Autowired private FulfillmentRepository fulfillments;
    @Autowired private OrderRepository orders;
    @Autowired private OrderStatusHistoryRepository history;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private PlayerVerificationService verification;
    @MockitoBean private SupplierClient supplier;
    @MockitoBean private SupplierClientRegistry supplierClients;

    private Order order;
    private SupplierProduct mapping;

    @BeforeEach
    void createPaidOrder() {
        when(supplierClients.getConfiguredClient()).thenReturn(supplier);
        when(supplierClients.getClient("mock")).thenReturn(supplier);
        when(supplier.key()).thenReturn("mock");
        when(supplier.name()).thenReturn("verification-test-supplier");
        when(supplier.fulfill(any())).thenReturn(SupplierTopupResponse.success("SUP-VERIFIED", "Delivered"));
        mapping = SupplierTestFixtures.createEligibleProduct(entityManager, transactionManager);
        order = new Order();
        order.setOrderNumber("PLAYER-" + UUID.randomUUID());
        order.setPackageId(mapping.getPackageId());
        order.setPlayerId("00123456");
        order.setZoneId("0012");
        order.setGameNameSnapshot("Snapshot display name");
        order.setPackageNameSnapshot("60 Diamonds");
        order.setUnitPrice(new BigDecimal("4.25"));
        order.setTotalAmount(new BigDecimal("4.25"));
        order.setCurrency("USD");
        order.setStatus(OrderStatus.PAID);
        order = orders.saveAndFlush(order);
    }

    @Test
    void verifiedAccountIsCheckedOutsideTheClaimTransactionBeforeDelivery() {
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    Fulfillment claimed = fulfillments.findByOrderId(order.getId()).orElseThrow();
                    assertThat(claimed.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
                    assertThat(claimed.getAttemptCount()).isZero();
                    assertThat(service.findAttempts(order.getId())).isEmpty();
                    verify(supplier, never()).fulfill(any());
                    return Optional.of(response(PlayerVerificationStatus.VERIFIED));
                });

        Fulfillment delivered = service.fulfillOrder(order.getId());

        assertThat(delivered.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(delivered.getAttemptCount()).isEqualTo(1);
        ArgumentCaptor<SupplierTopupRequest> request = ArgumentCaptor.forClass(SupplierTopupRequest.class);
        verify(supplier).fulfill(request.capture());
        assertThat(request.getValue()).extracting("playerId", "zoneId", "supplierProductId", "externalSku")
                .containsExactly("00123456", "0012", mapping.getId(), mapping.getExternalSku());
        verify(verification).revalidateForFulfillment(order.getPackageId(), "00123456", "0012");
    }

    @ParameterizedTest
    @EnumSource(value = PlayerVerificationStatus.class, names = {"INVALID", "UNAVAILABLE"})
    void unverifiedAccountRequiresReviewWithoutSubmittingATopup(PlayerVerificationStatus status) {
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenReturn(Optional.of(response(status)));

        Fulfillment failed = service.fulfillOrder(order.getId());
        service.fulfillOrder(order.getId());

        assertPreflightFailed(failed);
        verify(verification).revalidateForFulfillment(order.getPackageId(), "00123456", "0012");
    }

    @Test
    void lookupExceptionCannotLeakProviderDetailsOrSubmitATopup() {
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenThrow(new IllegalStateException("private-provider-credential"));

        Fulfillment failed = service.fulfillOrder(order.getId());

        assertPreflightFailed(failed);
        assertThat(failed.getLastError()).doesNotContain("private-provider-credential");
        assertThat(history.findAllByOrderIdOrderByChangedAtAsc(order.getId()))
                .extracting(OrderStatusHistory::getNote)
                .allSatisfy(note -> assertThat(note).doesNotContain("private-provider-credential"));
    }

    @Test
    void anExplicitRetryRechecksTheAccountBeforeDelivering() {
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenReturn(Optional.of(response(PlayerVerificationStatus.UNAVAILABLE)))
                .thenReturn(Optional.of(response(PlayerVerificationStatus.VERIFIED)));

        Fulfillment failed = service.fulfillOrder(order.getId());
        assertPreflightFailed(failed);
        Fulfillment retried = service.retryFulfillment(failed.getId());

        assertThat(retried.getId()).isEqualTo(failed.getId());
        assertThat(retried.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(retried.getSupplierProductId()).isEqualTo(mapping.getId());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(verification, times(2)).revalidateForFulfillment(order.getPackageId(), "00123456", "0012");
        verify(supplier).fulfill(any());
    }

    @Test
    void unsupportedLookupPreservesExistingDeliveryBehavior() {
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenReturn(Optional.empty());

        assertThat(service.fulfillOrder(order.getId()).getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);

        verify(verification).revalidateForFulfillment(order.getPackageId(), "00123456", "0012");
        verify(supplier).fulfill(any());
    }

    @Test
    void unknownDeliveryIsNeverResubmittedEvenWhenLookupWasVerified() {
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenReturn(Optional.of(response(PlayerVerificationStatus.VERIFIED)));
        when(supplier.fulfill(any())).thenThrow(new SupplierClient.SupplierException("mock", "Timeout"));

        Fulfillment unknown = service.fulfillOrder(order.getId());
        service.fulfillOrder(order.getId());
        service.retry(order.getId());

        assertThat(unknown.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(unknown.getAttemptCount()).isEqualTo(1);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        assertThat(service.findAttempts(order.getId())).singleElement()
                .extracting(FulfillmentAttempt::getStatus).isEqualTo(FulfillmentAttempt.Status.PENDING);
        verify(verification).revalidateForFulfillment(order.getPackageId(), "00123456", "0012");
        verify(supplier).fulfill(any());
    }

    @Test
    void concurrentCallerSeesTheCommittedClaimWhileLookupIsPending() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(verification.revalidateForFulfillment(order.getPackageId(), "00123456", "0012"))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Lookup test timed out");
                    }
                    return Optional.of(response(PlayerVerificationStatus.VERIFIED));
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.fulfillOrder(order.getId()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.fulfillOrder(order.getId()));
            Fulfillment duplicate = second.get(2, TimeUnit.SECONDS);
            assertThat(duplicate.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
            assertThat(duplicate.getAttemptCount()).isZero();
            verify(supplier, never()).fulfill(any());
            release.countDown();
            Fulfillment completed = first.get(5, TimeUnit.SECONDS);
            assertThat(completed.getId()).isEqualTo(duplicate.getId());
            assertThat(completed.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
            verify(verification).revalidateForFulfillment(order.getPackageId(), "00123456", "0012");
            verify(supplier).fulfill(any());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertPreflightFailed(Fulfillment failed) {
        assertThat(failed.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(failed.getAttemptCount()).isZero();
        assertThat(failed.getLastError()).isNotBlank();
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(service.findAttempts(order.getId())).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEEDS_REVIEW);
        verify(supplier, never()).fulfill(any());
    }

    private PlayerVerifyResponse response(PlayerVerificationStatus status) {
        return new PlayerVerifyResponse(status, "00123456", "0012",
                status == PlayerVerificationStatus.VERIFIED ? "Verified fixture" : null,
                status == PlayerVerificationStatus.VERIFIED ? "Fixture region" : null,
                "fixture-provider");
    }
}
