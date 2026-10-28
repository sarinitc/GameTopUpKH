package org.example.gametopupkh.integration;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogOrderPostgresTests extends PostgresIntegrationTest {

    @Test
    void catalogAdminWritesPublicReadsAndDeactivationArePersisted() throws Exception {
        String admin = token("ADMIN");
        UUID gameId = createGame(admin);
        String body = mvc.perform(post("/api/v1/admin/packages").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "gameId", gameId, "name", "86 Diamonds", "sku", "ML-86", "diamondAmount", 86,
                                "price", new BigDecimal("2.35"), "currency", "USD"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String packageId = json.readTree(body).path("data").path("id").asText();
        mvc.perform(get("/api/v1/games")).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
        mvc.perform(get("/api/v1/games/" + gameId + "/packages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sku").value("ML-86"));
        mvc.perform(patch("/api/v1/admin/packages/" + packageId + "/status")
                        .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/games/" + gameId + "/packages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "1.001", "10000000000"})
    void packagePriceBoundariesReturnValidationErrorInsteadOfDatabaseFailure(String price) throws Exception {
        String admin = token("ADMIN");
        UUID game = createGame(admin);
        mvc.perform(post("/api/v1/admin/packages").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "gameId", game, "name", "Invalid price", "sku", "BAD", "diamondAmount", 60,
                                "price", new BigDecimal(price), "currency", "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from game_packages", Integer.class)).isZero();
    }

    @Test
    void invalidGameSlugIsRejectedBeforePersistence() throws Exception {
        mvc.perform(post("/api/v1/admin/games").header("Authorization", "Bearer " + token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Invalid game\",\"slug\":\"invalid slug!\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from games", Integer.class)).isZero();
    }

    @Test
    void orderUsesServerPriceSnapshotAndRequiresOwnership() throws Exception {
        String owner = token("USER");
        UUID packageId = createPackage();
        String response = mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "packageId", packageId, "playerId", "12345", "price", 0.01,
                                "totalAmount", 0.01, "currency", "KHR", "quantity", 999))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.totalAmount").value(4.25))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andReturn().getResponse().getContentAsString();
        UUID order = UUID.fromString(json.readTree(response).path("data").path("id").asText());
        jdbc.update("update game_packages set price = 9.99 where id = ?", packageId);
        mvc.perform(get("/api/v1/orders/" + order).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalAmount").value(4.25));
        mvc.perform(get("/api/v1/orders/" + order).header("Authorization", "Bearer " + token("USER")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select quantity from orders where id = ?", Integer.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                Integer.class, order)).isEqualTo(1);
    }

    @Test
    void inactivePackageAndMissingRequiredZoneCannotCreateOrders() throws Exception {
        UUID pack = createPackage();
        String owner = token("USER");
        jdbc.update("update games set requires_zone_id = true where id = (select game_id from game_packages where id = ?)", pack);
        String payload = json.writeValueAsString(Map.of("packageId", pack, "playerId", "123"));
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ZONE_ID_REQUIRED"));
        jdbc.update("update game_packages set is_active = false where id = ?", pack);
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PACKAGE_INACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
    }

    private UUID createGame(String admin) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/games").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mobile Legends\",\"slug\":\"mobile-legends\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("data").path("id").asText());
    }
}
