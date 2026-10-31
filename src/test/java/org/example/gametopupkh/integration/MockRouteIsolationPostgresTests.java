package org.example.gametopupkh.integration;

import java.util.UUID;

import org.example.gametopupkh.fulfillment.DevFulfillmentController;
import org.example.gametopupkh.payment.MockPaymentController;
import org.example.gametopupkh.payment.gateway.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mock adapters must never expose development mutation routes in a production profile. */
@ActiveProfiles(value = {"prod", "test"}, inheritProfiles = false)
class MockRouteIsolationPostgresTests extends PostgresIntegrationTest {

    @Autowired private ApplicationContext context;

    @Test
    void productionProfileHasMockAdaptersButNoDevelopmentControllers() {
        assertThat(context.getEnvironment().getActiveProfiles()).contains("prod").doesNotContain("dev");
        assertThat(context.getBeansOfType(MockPaymentGateway.class)).hasSize(1);
        assertAll(
                () -> assertThat(context.getBeansOfType(MockPaymentController.class)).isEmpty(),
                () -> assertThat(context.getBeansOfType(DevFulfillmentController.class)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "DEV,anonymous", "DEV,admin",
            "CONFIRM_GET,anonymous", "CONFIRM_GET,admin",
            "CONFIRM_POST,anonymous", "CONFIRM_POST,admin"
    })
    void mockPaymentRoutesCannotApproveAPendingPayment(String endpoint, String actor) throws Exception {
        String customer = token("USER");
        UUID orderId = createOrder(customer);
        String created = mvc.perform(post("/api/v1/orders/" + orderId + "/payments/aba")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(json.readTree(created).path("data").path("paymentId").asText());
        String gatewayReference = json.readTree(created).path("data").path("gatewayReference").asText();
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "DEV" -> post("/api/v1/dev/payments/" + paymentId + "/success");
            case "CONFIRM_GET" -> get("/api/v1/payments/mock/" + gatewayReference + "/confirm");
            default -> post("/api/v1/payments/mock/" + gatewayReference + "/confirm");
        };
        if (actor.equals("admin")) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ADMIN"));
        }

        int responseStatus = mvc.perform(request).andReturn().getResponse().getStatus();
        assertAll(
                () -> assertThat(responseStatus).isIn(401, 403, 404),
                () -> assertThat(jdbc.queryForObject("select status from payments where id = ?",
                        String.class, paymentId)).isEqualTo("PENDING"),
                () -> assertThat(jdbc.queryForObject("select status from orders where id = ?",
                        String.class, orderId)).isEqualTo("PENDING_PAYMENT"),
                () -> assertThat(jdbc.queryForObject("select count(*) from fulfillments", Long.class)).isZero(),
                () -> assertThat(jdbc.queryForObject("select count(*) from fulfillment_attempts", Long.class)).isZero());
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "admin"})
    void mockFulfillmentRouteCannotDeliverAnOtherwiseEligiblePaidOrder(String actor) throws Exception {
        UUID orderId = createOrder(token("USER"));
        // Fixture only: isolate route authorization from payment verification and scheduled work.
        jdbc.update("update orders set status = 'PAID' where id = ?", orderId);
        MockHttpServletRequestBuilder request = post("/api/v1/dev/fulfillments/orders/" + orderId + "/process");
        if (actor.equals("admin")) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ADMIN"));
        }

        int responseStatus = mvc.perform(request).andReturn().getResponse().getStatus();
        assertAll(
                () -> assertThat(responseStatus).isIn(401, 403, 404),
                () -> assertThat(jdbc.queryForObject("select status from orders where id = ?",
                        String.class, orderId)).isEqualTo("PAID"),
                () -> assertThat(jdbc.queryForObject("select count(*) from fulfillments", Long.class)).isZero(),
                () -> assertThat(jdbc.queryForObject("select count(*) from fulfillment_attempts", Long.class)).isZero(),
                () -> assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                        Long.class, orderId)).isEqualTo(1));
    }
}
