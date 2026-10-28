package org.example.gametopupkh.integration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.example.gametopupkh.fulfillment.Fulfillment;
import org.example.gametopupkh.fulfillment.FulfillmentAttempt;
import org.example.gametopupkh.fulfillment.FulfillmentService;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.payment.PaymentService;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.example.gametopupkh.supplier.integration.MockSupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierClient;
import org.example.gametopupkh.supplier.integration.SupplierTopupResponse;
import org.example.gametopupkh.supplier.service.SupplierSelectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Exercises the production schema, locks and transaction boundaries with local mock providers only. */
class SupplierFulfillmentPostgresTests extends PostgresIntegrationTest {
    @Autowired private FulfillmentService fulfillmentService;
    @Autowired private PaymentService paymentService;
    @Autowired private SupplierSelectionService selection;
    @MockitoSpyBean private MockSupplierClient supplier;

    @Test
    void selectionUsesCurrencyAndActiveMappingsBeforeDeterministicCostTieBreak() throws Exception {
        UUID orderId = createOrder(token("USER"));
        UUID packageId = jdbc.queryForObject("select package_id from orders where id = ?", UUID.class, orderId);
        UUID supplierId = jdbc.queryForObject("select supplier_id from supplier_products where package_id = ?",
                UUID.class, packageId);
        jdbc.update("update supplier_products set cost_price = 9.00 where package_id = ?", packageId);
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        addMapping(second, supplierId, packageId, "2.00", "USD", true, old);
        addMapping(first, supplierId, packageId, "2.00", "USD", true, old);
        addMapping(UUID.randomUUID(), supplierId, packageId, "0.01", "KHR", true, old);
        addMapping(UUID.randomUUID(), supplierId, packageId, "0.01", "USD", false, old);
        UUID inactiveSupplier = UUID.randomUUID();
        jdbc.update("insert into suppliers (id, name, status) values (?, ?, 'SUSPENDED')",
                inactiveSupplier, "Suspended fixture");
        addMapping(UUID.randomUUID(), inactiveSupplier, packageId, "0.00", "USD", true, old);

        assertThat(selection.selectForPackage(packageId, "USD").getId()).isEqualTo(first);
        assertThat(selection.selectForPackage(packageId, "USD").getId()).isEqualTo(first);
    }

    @Test
    void repeatedPaymentEventsPersistOneDeliveryAndOneSuccessfulAttempt() throws Exception {
        UUID orderId = createOrder(token("USER"));
        CreatePaymentResponse payment = paymentFor(orderId);

        paymentService.processCallback(payment.reference(), "completion-event");
        paymentService.processCallback(payment.reference(), "completion-event");
        paymentService.processCallback(payment.reference(), "second-completion-event");

        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class,
                payment.paymentId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from fulfillments where order_id = ?", Integer.class,
                orderId)).isEqualTo(1);
        Fulfillment fulfillment = fulfillmentService.findByOrderId(orderId).orElseThrow();
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(fulfillment.getSupplierProductId()).isNotNull();
        assertThat(fulfillment.getSupplierSku()).isNotBlank();
        assertThat(fulfillment.getSupplierReference()).isNotBlank();
        assertThat(fulfillmentService.findAttempts(orderId)).singleElement()
                .extracting(FulfillmentAttempt::getStatus).isEqualTo(FulfillmentAttempt.Status.SUCCESS);
        verify(supplier, times(1)).fulfill(any());
    }

    @Test
    void timeoutLeavesUnknownDeliveryDurableAndAllRetryEntrypointsRefuseResubmission() throws Exception {
        UUID orderId = createOrder(token("USER"));
        CreatePaymentResponse payment = paymentFor(orderId);
        doThrow(new SupplierClient.SupplierException("mock", "Connection closed after submission"))
                .when(supplier).fulfill(any());

        paymentService.processCallback(payment.reference(), "unknown-delivery-event");
        Fulfillment first = fulfillmentService.findByOrderId(orderId).orElseThrow();
        paymentService.processCallback(payment.reference(), "unknown-delivery-event");
        paymentService.processCallback(payment.reference(), "another-delivery-event");
        fulfillmentService.fulfillOrder(orderId);
        fulfillmentService.retry(orderId);
        fulfillmentService.retryFulfillment(first.getId());

        Fulfillment persisted = fulfillmentService.findByOrderId(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
        assertThat(persisted.getCompletedAt()).isNull();
        assertThat(persisted.getLastError()).isNotBlank();
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class,
                payment.paymentId())).isEqualTo("COMPLETED");
        assertThat(fulfillmentService.findAttempts(orderId)).singleElement()
                .extracting(FulfillmentAttempt::getStatus).isEqualTo(FulfillmentAttempt.Status.PENDING);
        verify(supplier, times(1)).fulfill(any());
    }

    @Test
    void unavailableMappingPersistsReviewWithoutCallingSupplier() throws Exception {
        UUID orderId = createOrder(token("USER"));
        jdbc.update("update orders set status = 'PAID' where id = ?", orderId);
        jdbc.update("update supplier_products set is_active = false where package_id = "
                + "(select package_id from orders where id = ?)", orderId);

        Fulfillment fulfillment = fulfillmentService.fulfillOrder(orderId);

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(fulfillment.getAttemptCount()).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("NEEDS_REVIEW");
        assertThat(fulfillmentService.findAttempts(orderId)).isEmpty();
        verify(supplier, times(0)).fulfill(any());
    }

    @Test
    void confirmedRejectionCanBeRetriedUsingTheStoredSelection() throws Exception {
        UUID orderId = createOrder(token("USER"));
        jdbc.update("update orders set status = 'PAID' where id = ?", orderId);
        doReturn(SupplierTopupResponse.failure("Definitive rejection")).when(supplier).fulfill(any());
        Fulfillment rejected = fulfillmentService.fulfillOrder(orderId);
        assertThat(rejected.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        UUID originalMapping = rejected.getSupplierProductId();
        String originalSku = rejected.getSupplierSku();
        jdbc.update("update supplier_products set external_sku = 'changed-after-rejection' where id = ?",
                originalMapping);
        doReturn(SupplierTopupResponse.success("MOCK-RETRY", "Delivered")).when(supplier).fulfill(any());

        Fulfillment retried = fulfillmentService.retry(orderId);

        assertThat(retried.getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(retried.getSupplierProductId()).isEqualTo(originalMapping);
        assertThat(retried.getSupplierSku()).isEqualTo(originalSku);
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        var requests = org.mockito.ArgumentCaptor.forClass(
                org.example.gametopupkh.supplier.integration.SupplierTopupRequest.class);
        verify(supplier, times(retried.getAttemptCount())).fulfill(requests.capture());
        assertThat(requests.getAllValues()).extracting("supplierProductId", "externalSku")
                .containsOnly(org.assertj.core.groups.Tuple.tuple(originalMapping, originalSku));
    }

    @Test
    void concurrentDeliverySeesCommittedClaimAndDoesNotCallSupplierAgain() throws Exception {
        UUID orderId = createOrder(token("USER"));
        jdbc.update("update orders set status = 'PAID' where id = ?", orderId);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Supplier fixture was not released");
            }
            return SupplierTopupResponse.success("MOCK-CONCURRENT", "Delivered");
        }).when(supplier).fulfill(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> fulfillmentService.fulfillOrder(orderId));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from fulfillments where order_id = ? "
                    + "and status = 'PROCESSING' and attempt_count = 1", Integer.class, orderId)).isEqualTo(1);
            assertThat(fulfillmentService.findAttempts(orderId)).singleElement()
                    .extracting(FulfillmentAttempt::getStatus).isEqualTo(FulfillmentAttempt.Status.PENDING);
            var second = executor.submit(() -> fulfillmentService.fulfillOrder(orderId));
            assertThat(second.get(2, TimeUnit.SECONDS).getStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
            verify(supplier, times(1)).fulfill(any());
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
            assertThat(fulfillmentService.findAttempts(orderId)).hasSize(1);
            assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CreatePaymentResponse paymentFor(UUID orderId) {
        UUID ownerId = jdbc.queryForObject("select user_id from orders where id = ?", UUID.class, orderId);
        return paymentService.createAbaPayment(orderId, ownerId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private void addMapping(UUID id, UUID supplierId, UUID packageId, String cost, String currency,
            boolean active, Instant created) {
        jdbc.update("insert into supplier_products "
                        + "(id, supplier_id, package_id, external_sku, cost_price, currency, is_active, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, supplierId, packageId, "sku-" + id, new BigDecimal(cost), currency, active,
                Timestamp.from(created));
    }
}
