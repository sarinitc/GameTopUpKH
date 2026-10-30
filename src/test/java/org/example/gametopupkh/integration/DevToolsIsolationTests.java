package org.example.gametopupkh.integration;

import org.example.gametopupkh.fulfillment.DevFulfillmentController;
import org.example.gametopupkh.payment.MockPaymentController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Development mutation routes must not exist unless they are explicitly enabled.
 * The default is false, so a deployment that forgets to set the property stays closed.
 */
@SpringBootTest(properties = "app.dev-tools.enabled=false")
@AutoConfigureMockMvc
class DevToolsIsolationTests {

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mvc;

    @Test
    void developmentMutationRoutesAreAbsentWhenDevToolsAreDisabled() throws Exception {
        assertThat(context.getBeansOfType(MockPaymentController.class)).isEmpty();
        assertThat(context.getBeansOfType(DevFulfillmentController.class)).isEmpty();

        mvc.perform(post("/api/v1/dev/payments/00000000-0000-0000-0000-000000000000/success"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/dev/fulfillments/orders/00000000-0000-0000-0000-000000000000/process"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/mock/unknown-reference/confirm"))
                .andExpect(status().isNotFound());
    }
}
