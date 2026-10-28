package org.example.gametopupkh.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:actuator-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=actuator-api-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock",
        "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
class ActuatorApiTests {

    @Autowired private MockMvc mvc;
    @LocalServerPort private int port;
    @MockitoSpyBean private DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthProbesArePublicAndHideDetails(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SUPPORT"})
    void nonAdminHealthAlsoHidesComponents(String role) throws Exception {
        mvc.perform(get("/actuator/health").with(user("reader").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void adminCanInspectDatabaseAndDiskHealth() throws Exception {
        mvc.perform(get("/actuator/health").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.diskSpace.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator/metrics", "/actuator/metrics/jvm.memory.used",
            "/actuator/health/db"})
    void operationalEndpointsRequireAdmin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        for (String role : new String[] {"USER", "SUPPORT"}) {
            mvc.perform(get(path).with(user("reader").roles(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void adminInfoContainsOnlyCuratedApplicationMetadata() throws Exception {
        mvc.perform(get("/actuator/info").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("GameTopUp-KH"))
                .andExpect(jsonPath("$.app.description").isNotEmpty())
                .andExpect(jsonPath("$.app.version").isNotEmpty())
                .andExpect(jsonPath("$.app.version", not(containsString("@"))))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void adminCanReadRuntimeAndHttpMetrics() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        mvc.perform(get("/actuator/metrics").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItems("jvm.memory.used", "jvm.threads.live", "process.uptime",
                        "system.cpu.usage", "jdbc.connections.active", "http.server.requests")));
        mvc.perform(get("/actuator/metrics/http.server.requests")
                        .param("tag", "uri:/actuator/health").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value", hasItem(greaterThan(0.0))))
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'application')].values[*]",
                        hasItem("GameTopUp-KH")));
    }

    @Test
    void databaseOutageFailsReadinessAndHealthButNotLiveness() throws Exception {
        doThrow(new SQLException("Simulated database outage")).when(dataSource).getConnection();
        try {
            for (String path : new String[] {"/actuator/health", "/actuator/health/readiness"}) {
                mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.status").value("DOWN"))
                        .andExpect(jsonPath("$.components").doesNotExist())
                        .andExpect(jsonPath("$.details").doesNotExist());
            }
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            reset(dataSource);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"health", "info", "metrics"})
    void monitoringEndpointsRejectWritesEvenForAdmin(String endpoint) throws Exception {
        mvc.perform(post("/actuator/" + endpoint).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "heapdump", "loggers", "shutdown"})
    void sensitiveAndMutatingEndpointsStayBlockedEvenForAdmin(String endpoint) throws Exception {
        mvc.perform(get("/actuator/" + endpoint).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/actuator/" + endpoint).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerTokenStillReturnsUnauthorized() throws Exception {
        mvc.perform(get("/actuator/health").header("Authorization", "Bearer invalid-test-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
