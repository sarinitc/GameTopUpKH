package org.example.gametopupkh.security;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "APP_CORS_ALLOWED_ORIGINS=https://gametopupkh.cheavsaren65.workers.dev"
})
@AutoConfigureMockMvc
class WorkersCorsConfigurationTests {

    private static final String WORKERS_ORIGIN = "https://gametopupkh.cheavsaren65.workers.dev";
    private static final String UNKNOWN_ORIGIN = "https://attacker.example.com";

    @Autowired
    private MockMvc mvc;

    @Test
    void preflightFromWorkersOriginIsAccepted() throws Exception {
        mvc.perform(options("/api/v1/orders/my")
                        .header(HttpHeaders.ORIGIN, WORKERS_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WORKERS_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        Matchers.containsStringIgnoringCase("authorization")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void workersOriginCanCallPublicCatalog() throws Exception {
        mvc.perform(get("/api/v1/games").header(HttpHeaders.ORIGIN, WORKERS_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WORKERS_ORIGIN));
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        mvc.perform(get("/api/v1/games").header(HttpHeaders.ORIGIN, UNKNOWN_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
