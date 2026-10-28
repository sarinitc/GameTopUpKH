package org.example.gametopupkh.integration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.example.gametopupkh.payment.aba.AbaPayWayClient;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionRequest;
import org.example.gametopupkh.payment.aba.dto.AbaCheckTransactionResponse;
import org.example.gametopupkh.payment.aba.dto.AbaQrResponse;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentGatewayException;
import org.example.gametopupkh.payment.gateway.PaymentGateway.PaymentRequest;
import org.example.gametopupkh.payment.reconciliation.PaymentReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL transactions, security, ABA mapping and fulfillment; provider transport is mocked. */
@SpringBootTest(properties = {
        "payment.gateway=aba", "payment.reconcile.cooldown=PT0S",
        "payment.reconciliation.min-age=PT1M", "payment.aba.callback-secret=",
        "supplier.client=mock", "supplier.mock.fail=false"
})
class PaymentPostgresTests extends PostgresIntegrationTest {

    @MockitoBean private AbaPayWayClient client;
    @Autowired private PaymentReconciliationService reconciliation;

    @BeforeEach
    void stubQrTransport() {
        when(client.createPayment(any())).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0);
            assertThat(jdbc.queryForObject("select status from payments where payment_reference = ?",
                    String.class, request.transactionId())).isEqualTo("INITIALIZING");
            return new AbaQrResponse(json.valueToTree(Map.of(
                    "status", Map.of("code", "0", "tran_id", request.transactionId()),
                    "qrString", "test-only-qr")));
        });
    }

    @Test
    void qrCreationReservesOnePaymentAndDoesNotPayOrDeliver() throws Exception {
        String owner = token("USER");
        PaymentFixture payment = createPayment(owner);

        mvc.perform(post("/api/v1/orders/{id}/payments/aba", payment.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentId").value(payment.id().toString()))
                .andExpect(jsonPath("$.data.reference").value(payment.reference()))
                .andExpect(jsonPath("$.data.qrString").value("test-only-qr"));

        assertState(payment, "PENDING", "PENDING_PAYMENT");
        assertThat(count("select count(*) from payments where order_id = ?", payment.orderId())).isEqualTo(1);
        assertNoDelivery(payment);
        verify(client, times(1)).createPayment(any());
        verify(client, never()).checkTransaction(any());
    }

    @Test
    void untrustedApprovedCallbackCannotOverridePendingProviderStatus() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        stubStatus(payment, "PENDING");

        callback(payment, "forged-approved");

        assertState(payment, "PENDING", "PENDING_PAYMENT");
        assertNoDelivery(payment);
        verify(client).checkTransaction(new AbaCheckTransactionRequest(payment.reference()));
    }

    @Test
    void verifiedCallbackCompletesPaymentAndDeliversExactlyOnceAcrossDuplicates() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        stubStatus(payment, "APPROVED");

        callback(payment, "approved");
        callback(payment, "approved");
        callback(payment, "another-provider-event");

        assertDeliveredOnce(payment);
        assertThat(count("select count(*) from payment_events where payment_id = ? and event_key = 'approved'",
                payment.id())).isEqualTo(1);
        assertThat(count("select count(*) from order_status_history where order_id = ? and status = 'PAID'",
                payment.orderId())).isEqualTo(1);
        verify(client, times(1)).checkTransaction(any());
    }

    @Test
    void simultaneousDuplicateCallbacksShareOnePaymentTransitionAndDelivery() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        CountDownLatch bothChecking = new CountDownLatch(2);
        when(client.checkTransaction(any())).thenAnswer(invocation -> {
            bothChecking.countDown();
            assertThat(bothChecking.await(10, TimeUnit.SECONDS)).isTrue();
            return providerResponse(payment.reference(), "APPROVED", payment.amount(), "USD");
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { callback(payment, "concurrent"); return null; });
            var second = executor.submit(() -> { callback(payment, "concurrent"); return null; });
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertDeliveredOnce(payment);
        assertThat(count("select count(*) from payment_events where payment_id = ? and event_key = 'concurrent'",
                payment.id())).isEqualTo(1);
        assertThat(count("select count(*) from order_status_history where order_id = ? and status = 'PAID'",
                payment.orderId())).isEqualTo(1);
    }

    enum InvalidConfirmation { AMOUNT, CURRENCY, REFERENCE, MISSING_AMOUNT, MISSING_CURRENCY }

    @ParameterizedTest
    @EnumSource(InvalidConfirmation.class)
    void providerConfirmationMustMatchReservedMoneyAndReference(InvalidConfirmation invalid) throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        when(client.checkTransaction(any())).thenReturn(invalidResponse(payment, invalid));

        callback(payment, "invalid-confirmation");

        assertState(payment, "NEEDS_REVIEW", "PENDING_PAYMENT");
        assertNoDelivery(payment);
        assertThat(jdbc.queryForObject("select gateway_reference from payments where id = ?", String.class,
                payment.id())).isEqualTo(payment.reference());
        assertThat(count("select count(*) from payments where id = ? and paid_at is not null", payment.id())).isZero();
    }

    @ParameterizedTest
    @EnumSource(InvalidConfirmation.class)
    void reconciliationAlsoRejectsMismatchedOrIncompleteProviderConfirmation(InvalidConfirmation invalid) throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        when(client.checkTransaction(any())).thenReturn(invalidResponse(payment, invalid));

        mvc.perform(post("/api/v1/admin/payments/{id}/reconcile", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NEEDS_REVIEW"));

        assertState(payment, "NEEDS_REVIEW", "PENDING_PAYMENT");
        assertNoDelivery(payment);
        assertThat(jdbc.queryForObject("select gateway_reference from payments where id = ?", String.class,
                payment.id())).isEqualTo(payment.reference());
    }

    @Test
    void failedStatusLookupKeepsPaymentPendingAndSameCallbackCanRetry() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        when(client.checkTransaction(any())).thenThrow(new PaymentGatewayException("aba", "transport failure"))
                .thenReturn(providerResponse(payment.reference(), "APPROVED", payment.amount(), "USD"));

        mvc.perform(post("/api/v1/payments/aba/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(callbackBody(payment, "retryable")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        assertState(payment, "PENDING", "PENDING_PAYMENT");
        assertNoDelivery(payment);
        assertThat(count("select count(*) from payment_events where payment_id = ? and type = 'CALLBACK_ERROR'",
                payment.id())).isEqualTo(1);
        assertThat(count("select count(*) from payment_events where payment_id = ? and event_key = 'retryable'",
                payment.id())).isZero();

        callback(payment, "retryable");
        assertDeliveredOnce(payment);
    }

    @Test
    void uncertainQrCreationCannotIssueAnotherCharge() throws Exception {
        String owner = token("USER");
        UUID orderId = createOrder(owner);
        org.mockito.Mockito.doThrow(new PaymentGatewayException("aba", "response unavailable")).when(client).createPayment(any());

        mvc.perform(post("/api/v1/orders/{id}/payments/aba", orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isBadGateway());
        mvc.perform(post("/api/v1/orders/{id}/payments/aba", orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REQUIRES_REVIEW"));

        assertThat(jdbc.queryForObject("select status from payments where order_id = ?", String.class, orderId))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(count("select count(*) from payments where order_id = ?", orderId)).isEqualTo(1);
        verify(client, times(1)).createPayment(any());
    }

    @Test
    void overlappingPaymentCreationCannotSendASecondProviderRequest() throws Exception {
        String owner = token("USER");
        UUID orderId = createOrder(owner);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0);
            providerEntered.countDown();
            assertThat(releaseProvider.await(10, TimeUnit.SECONDS)).isTrue();
            return new AbaQrResponse(json.valueToTree(Map.of("status",
                    Map.of("code", "0", "tran_id", request.transactionId()), "qrString", "test-only-qr")));
        }).when(client).createPayment(any());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> mvc.perform(post("/api/v1/orders/{id}/payments/aba", orderId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                    .andExpect(status().isCreated()).andReturn());
            try {
                assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(post("/api/v1/orders/{id}/payments/aba", orderId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
            } finally {
                releaseProvider.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("select count(*) from payments where order_id = ?", orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments where order_id = ?", String.class, orderId))
                .isEqualTo("PENDING");
        verify(client, times(1)).createPayment(any());
    }

    @Test
    void reconciliationRequiresAdminAndVerifiesProviderBeforeDelivery() throws Exception {
        String owner = token("USER");
        String admin = token("ADMIN");
        PaymentFixture payment = createPayment(owner);
        stubStatus(payment, "APPROVED");

        mvc.perform(post("/api/v1/admin/payments/{id}/reconcile", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isForbidden());
        verify(client, never()).checkTransaction(any());
        mvc.perform(post("/api/v1/admin/payments/{id}/reconcile", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertDeliveredOnce(payment);
        assertThat(count("select count(*) from payment_events where payment_id = ? and type = 'RECONCILE' "
                + "and actor_user_id is not null", payment.id())).isEqualTo(1);
    }

    @Test
    void batchReconciliationIsolatesProviderErrorsAndSkipsRecentPayments() throws Exception {
        String owner = token("USER");
        PaymentFixture failed = createPayment(owner);
        PaymentFixture approved = createPayment(owner);
        PaymentFixture recent = createPayment(owner);
        jdbc.update("update payments set created_at = now() - interval '5 minutes' where id in (?, ?)",
                failed.id(), approved.id());
        when(client.checkTransaction(any())).thenAnswer(invocation -> {
            AbaCheckTransactionRequest request = invocation.getArgument(0);
            if (request.gatewayReference().equals(failed.reference())) {
                throw new PaymentGatewayException("aba", "transport unavailable");
            }
            assertThat(request.gatewayReference()).isEqualTo(approved.reference());
            return providerResponse(approved.reference(), "APPROVED", approved.amount(), "USD");
        });

        var result = reconciliation.reconcile();

        assertThat(result.inspected()).isEqualTo(2);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        assertState(failed, "PENDING", "PENDING_PAYMENT");
        assertState(recent, "PENDING", "PENDING_PAYMENT");
        assertDeliveredOnce(approved);
        verify(client, times(2)).checkTransaction(any());
    }

    @Test
    void paymentReadAndCreationEnforceOwnership() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        String other = token("USER");

        mvc.perform(get("/api/v1/payments/{id}", payment.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/{id}/payments/aba", payment.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/{id}", payment.id())).andExpect(status().isUnauthorized());
        assertThat(count("select count(*) from payments where order_id = ?", payment.orderId())).isEqualTo(1);
        verify(client, times(1)).createPayment(any());
    }

    @Test
    void abaRefundIsAdminOnlyPendingAndIdempotentWithoutPretendingMoneyWasReturned() throws Exception {
        String owner = token("USER");
        String admin = token("ADMIN");
        PaymentFixture payment = createPayment(owner);
        stubStatus(payment, "APPROVED");
        callback(payment, "paid");

        mvc.perform(post("/api/v1/admin/payments/{id}/refunds", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)).andExpect(status().isForbidden());
        JsonNode first = requestRefund(payment, admin, "{\"reason\":\"Customer request\"}");
        JsonNode duplicate = requestRefund(payment, admin, "{\"reason\":\"Repeated request\"}");

        assertThat(first.path("status").asText()).isEqualTo("PENDING");
        assertThat(first.path("refundId").asText()).isEqualTo(duplicate.path("refundId").asText());
        assertThat(first.path("amount").decimalValue()).isEqualByComparingTo(payment.amount());
        assertThat(count("select count(*) from refunds where payment_id = ?", payment.id())).isEqualTo(1);
        assertThat(count("select count(*) from refunds where payment_id = ? and provider_reference is null "
                + "and completed_at is null and requested_by is not null", payment.id())).isEqualTo(1);
        assertState(payment, "COMPLETED", "COMPLETED");
        mvc.perform(get("/api/v1/orders/{id}/refunds", payment.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("USER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/{id}/refunds", payment.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void simultaneousRefundRequestsCreateOneDurableRequest() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        String admin = token("ADMIN");
        stubStatus(payment, "APPROVED");
        callback(payment, "paid");
        CountDownLatch ready = new CountDownLatch(2);

        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> request = () -> {
                ready.countDown();
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                return requestRefund(payment, admin, "{}").path("refundId").asText();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("select count(*) from refunds where payment_id = ?", payment.id())).isEqualTo(1);
        assertState(payment, "COMPLETED", "COMPLETED");
    }

    @Test
    void partialAbaRefundPreservesExactAmountAndDoesNotChangeOrderStatus() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        stubStatus(payment, "APPROVED");
        callback(payment, "paid");

        JsonNode refund = requestRefund(payment, token("ADMIN"), "{\"amount\":1.25,\"reason\":\"Partial\"}");

        assertThat(refund.path("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select amount from refunds where payment_id = ?", BigDecimal.class,
                payment.id())).isEqualByComparingTo("1.25");
        assertState(payment, "COMPLETED", "COMPLETED");
    }

    @Test
    void refundRejectsExcessAmountAndUnresolvedDelivery() throws Exception {
        PaymentFixture payment = createPayment(token("USER"));
        String admin = token("ADMIN");
        stubStatus(payment, "APPROVED");
        callback(payment, "paid");

        mvc.perform(post("/api/v1/admin/payments/{id}/refunds", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", payment.amount().add(BigDecimal.ONE)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDED"));
        jdbc.update("update fulfillments set status = 'PROCESSING', completed_at = null where order_id = ?",
                payment.orderId());
        jdbc.update("update orders set status = 'NEEDS_REVIEW' where id = ?", payment.orderId());
        mvc.perform(post("/api/v1/admin/payments/{id}/refunds", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_UNRESOLVED"));
        assertThat(count("select count(*) from refunds where payment_id = ?", payment.id())).isZero();
        assertState(payment, "COMPLETED", "NEEDS_REVIEW");
    }

    private PaymentFixture createPayment(String owner) throws Exception {
        UUID orderId = createOrder(owner);
        JsonNode data = json.readTree(mvc.perform(post("/api/v1/orders/{id}/payments/aba", orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString()).path("data");
        return new PaymentFixture(UUID.fromString(data.path("paymentId").asText()), orderId,
                data.path("reference").asText(), data.path("amount").decimalValue());
    }

    private void stubStatus(PaymentFixture payment, String providerStatus) {
        when(client.checkTransaction(new AbaCheckTransactionRequest(payment.reference())))
                .thenReturn(providerResponse(payment.reference(), providerStatus, payment.amount(), "USD"));
    }

    private AbaCheckTransactionResponse invalidResponse(PaymentFixture payment, InvalidConfirmation invalid) {
        String reference = invalid == InvalidConfirmation.REFERENCE ? "unrelated-payment" : payment.reference();
        BigDecimal amount = invalid == InvalidConfirmation.AMOUNT ? payment.amount().add(BigDecimal.ONE)
                : invalid == InvalidConfirmation.MISSING_AMOUNT ? null : payment.amount();
        String currency = invalid == InvalidConfirmation.CURRENCY ? "KHR"
                : invalid == InvalidConfirmation.MISSING_CURRENCY ? null : "USD";
        return providerResponse(reference, "APPROVED", amount, currency);
    }

    private AbaCheckTransactionResponse providerResponse(String reference, String providerStatus,
            BigDecimal amount, String currency) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment_status", providerStatus);
        data.put("total_amount", amount);
        data.put("payment_currency", currency);
        return new AbaCheckTransactionResponse(json.valueToTree(Map.of(
                "status", Map.of("code", "00", "tran_id", reference), "data", data)));
    }

    private String callbackBody(PaymentFixture payment, String eventId) {
        return json.writeValueAsString(Map.of("reference", payment.reference(), "event_id", eventId,
                "status", "APPROVED", "amount", payment.amount(), "currency", "USD"));
    }

    private void callback(PaymentFixture payment, String eventId) throws Exception {
        mvc.perform(post("/api/v1/payments/aba/callback").contentType(MediaType.APPLICATION_JSON)
                        .content(callbackBody(payment, eventId))).andExpect(status().isOk());
    }

    private JsonNode requestRefund(PaymentFixture payment, String admin, String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/admin/payments/{id}/refunds", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void assertState(PaymentFixture payment, String paymentStatus, String orderStatus) {
        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class, payment.id()))
                .isEqualTo(paymentStatus);
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, payment.orderId()))
                .isEqualTo(orderStatus);
    }

    private void assertNoDelivery(PaymentFixture payment) {
        assertThat(count("select count(*) from fulfillments where order_id = ?", payment.orderId())).isZero();
        assertThat(count("select count(*) from order_status_history where order_id = ? and status = 'PAID'",
                payment.orderId())).isZero();
    }

    private void assertDeliveredOnce(PaymentFixture payment) {
        assertState(payment, "COMPLETED", "COMPLETED");
        assertThat(count("select count(*) from fulfillments where order_id = ? and status = 'SUCCEEDED' "
                + "and attempt_count = 1", payment.orderId())).isEqualTo(1);
        assertThat(count("select count(*) from fulfillment_attempts a join fulfillments f on a.fulfillment_id = f.id "
                + "where f.order_id = ?", payment.orderId())).isEqualTo(1);
        assertThat(count("select count(*) from payments where id = ? and paid_at is not null", payment.id())).isEqualTo(1);
    }

    private int count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private record PaymentFixture(UUID id, UUID orderId, String reference, BigDecimal amount) { }
}
