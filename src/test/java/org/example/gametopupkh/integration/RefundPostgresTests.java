package org.example.gametopupkh.integration;

import java.util.UUID;

import org.example.gametopupkh.payment.PaymentService;
import org.example.gametopupkh.payment.dto.CreatePaymentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundPostgresTests extends PostgresIntegrationTest {

    @Autowired private PaymentService payments;

    @Test
    void repeatedFullMockRefundCreatesOneRefundAndOneRefundedTransition() throws Exception {
        String owner = token("USER");
        String admin = token("ADMIN");
        CreatePaymentResponse payment = completedPayment(owner);
        String path = "/api/v1/admin/payments/" + payment.paymentId() + "/refunds";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(path).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.amount").value(4.25));
        }
        assertThat(jdbc.queryForObject("select count(*) from refunds where payment_id = ?",
                Integer.class, payment.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ? and status = 'REFUNDED'",
                Integer.class, payment.orderId())).isEqualTo(1);
        mvc.perform(get("/api/v1/orders/" + payment.orderId() + "/refunds")
                        .header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + payment.orderId() + "/refunds")
                        .header("Authorization", "Bearer " + token("USER"))).andExpect(status().isNotFound());
    }

    @Test
    void partialMockRefundKeepsCompletedOrderAndDoesNotRedeliver() throws Exception {
        CreatePaymentResponse payment = completedPayment(token("USER"));
        mvc.perform(post("/api/v1/admin/payments/" + payment.paymentId() + "/refunds")
                        .header("Authorization", "Bearer " + token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.25,\"reason\":\"QA partial refund\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, payment.orderId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from fulfillment_attempts", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0", "4.26"})
    void invalidRefundAmountsHaveNoFinancialSideEffects(String amount) throws Exception {
        CreatePaymentResponse payment = completedPayment(token("USER"));
        mvc.perform(post("/api/v1/admin/payments/" + payment.paymentId() + "/refunds")
                        .header("Authorization", "Bearer " + token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, payment.orderId()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void supportCanReadButCannotRequestRefunds() throws Exception {
        CreatePaymentResponse payment = completedPayment(token("USER"));
        String support = token("SUPPORT");
        mvc.perform(get("/api/v1/admin/refunds").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/payments/" + payment.paymentId() + "/refunds")
                        .header("Authorization", "Bearer " + support)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from refunds", Integer.class)).isZero();
    }

    private CreatePaymentResponse completedPayment(String owner) throws Exception {
        UUID order = createOrder(owner);
        CreatePaymentResponse payment = payments.createAbaPayment(order, jwt.extractUserId(owner));
        payments.confirmMockPayment(payment.reference());
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order)).isEqualTo("COMPLETED");
        return payment;
    }
}
