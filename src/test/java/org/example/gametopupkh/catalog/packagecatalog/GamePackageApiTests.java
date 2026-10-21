package org.example.gametopupkh.catalog.packagecatalog;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class GamePackageApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createPackageReturnsCreatedPackage() throws Exception {
        String gameId = createGame();
        String sku = uniqueSku();

        mockMvc.perform(post("/api/v1/admin/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gameId, sku, "10.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.gameId").value(gameId))
                .andExpect(jsonPath("$.data.name").value("60 Diamonds"))
                .andExpect(jsonPath("$.data.sku").value(sku))
                .andExpect(jsonPath("$.data.diamondAmount").value(60))
                .andExpect(jsonPath("$.data.price").value(10.00))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void createPackageRejectsDuplicateSku() throws Exception {
        String gameId = createGame();
        String sku = uniqueSku();
        createPackage(gameId, sku);

        mockMvc.perform(post("/api/v1/admin/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gameId, sku, "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_SKU_EXISTS"));
    }

    @Test
    void createPackageRejectsUnknownGame() throws Exception {
        mockMvc.perform(post("/api/v1/admin/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID().toString(), uniqueSku(), "20.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void createPackageRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/admin/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"diamondAmount\":0,\"price\":-5,\"currency\":\"usd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listReturnsActivePackagesOfTheGameOnly() throws Exception {
        String gameId = createGame();
        String otherGameId = createGame();
        String sku = uniqueSku();
        String packageId = createPackage(gameId, sku);
        createPackage(otherGameId, uniqueSku());

        mockMvc.perform(get("/api/v1/games/" + gameId + "/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(packageId))
                .andExpect(jsonPath("$.data[0].sku").value(sku));

        mockMvc.perform(patch("/api/v1/admin/packages/" + packageId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(get("/api/v1/games/" + gameId + "/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void listReturnsNotFoundForUnknownGame() throws Exception {
        mockMvc.perform(get("/api/v1/games/" + UUID.randomUUID() + "/packages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void getByIdReturnsPackage() throws Exception {
        String gameId = createGame();
        String packageId = createPackage(gameId, uniqueSku());

        mockMvc.perform(get("/api/v1/packages/" + packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(packageId))
                .andExpect(jsonPath("$.data.gameId").value(gameId));
    }

    @Test
    void getByIdReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/packages/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_FOUND"));
    }

    @Test
    void updatePackageReplacesMutableFields() throws Exception {
        String gameId = createGame();
        String packageId = createPackage(gameId, uniqueSku());

        mockMvc.perform(put("/api/v1/admin/packages/" + packageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"120 Diamonds\",\"diamondAmount\":120,\"price\":19.50,\"currency\":\"KHR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(packageId))
                .andExpect(jsonPath("$.data.name").value("120 Diamonds"))
                .andExpect(jsonPath("$.data.diamondAmount").value(120))
                .andExpect(jsonPath("$.data.price").value(19.50))
                .andExpect(jsonPath("$.data.currency").value("KHR"));
    }

    @Test
    void updatePackageReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(put("/api/v1/admin/packages/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"120 Diamonds\",\"diamondAmount\":120,\"price\":19.50}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_FOUND"));
    }

    @Test
    void patchStatusTogglesActiveFlag() throws Exception {
        String gameId = createGame();
        String packageId = createPackage(gameId, uniqueSku());

        mockMvc.perform(patch("/api/v1/admin/packages/" + packageId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(patch("/api/v1/admin/packages/" + packageId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    private String createGame() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Game\",\"slug\":\"pkg-test-" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"playerIdLabel\":\"User ID\",\"requiresZoneId\":false}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createPackage(String gameId, String sku) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(gameId, sku, "10.00")))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createBody(String gameId, String sku, String price) {
        return "{\"gameId\":\"" + gameId + "\",\"name\":\"60 Diamonds\",\"sku\":\"" + sku
                + "\",\"diamondAmount\":60,\"price\":" + price + "}";
    }

    private String uniqueSku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
