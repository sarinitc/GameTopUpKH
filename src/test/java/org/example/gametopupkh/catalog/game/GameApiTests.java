package org.example.gametopupkh.catalog.game;

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
class GameApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createGameReturnsCreatedGame() throws Exception {
        String slug = uniqueSlug();

        mockMvc.perform(post("/api/v1/admin/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "Mobile Legends", "User ID", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Mobile Legends"))
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.playerIdLabel").value("User ID"))
                .andExpect(jsonPath("$.data.requiresZoneId").value(true))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void createGameRejectsDuplicateSlug() throws Exception {
        String slug = uniqueSlug();
        createGame(slug);

        mockMvc.perform(post("/api/v1/admin/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "Duplicate", "User ID", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("GAME_SLUG_EXISTS"));
    }

    @Test
    void createGameRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/admin/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"slug\":\"Not A Slug\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listReturnsActiveGamesOnly() throws Exception {
        String slug = uniqueSlug();
        String id = createGame(slug);

        mockMvc.perform(get("/api/v1/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.slug == '" + slug + "')]").isNotEmpty());

        mockMvc.perform(patch("/api/v1/admin/games/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(get("/api/v1/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')]").isEmpty());
    }

    @Test
    void getByIdReturnsGame() throws Exception {
        String slug = uniqueSlug();
        String id = createGame(slug);

        mockMvc.perform(get("/api/v1/games/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.slug").value(slug));
    }

    @Test
    void getByIdReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/games/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void updateGameReplacesMutableFields() throws Exception {
        String slug = uniqueSlug();
        String id = createGame(slug);

        mockMvc.perform(put("/api/v1/admin/games/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Game\",\"playerIdLabel\":\"Player Code\",\"requiresZoneId\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.name").value("Renamed Game"))
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.playerIdLabel").value("Player Code"))
                .andExpect(jsonPath("$.data.requiresZoneId").value(false));
    }

    @Test
    void updateGameReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(put("/api/v1/admin/games/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Game\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void updateGameRejectsMissingName() throws Exception {
        String id = createGame(uniqueSlug());

        mockMvc.perform(put("/api/v1/admin/games/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIdLabel\":\"Player Code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void patchStatusRejectsMissingActiveFlag() throws Exception {
        String id = createGame(uniqueSlug());

        mockMvc.perform(patch("/api/v1/admin/games/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void patchStatusTogglesActiveFlag() throws Exception {
        String slug = uniqueSlug();
        String id = createGame(slug);

        mockMvc.perform(patch("/api/v1/admin/games/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(patch("/api/v1/admin/games/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    private String createGame(String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "Test Game", "User ID", false)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createBody(String slug, String name, String playerIdLabel, boolean requiresZoneId) {
        return "{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\",\"playerIdLabel\":\""
                + playerIdLabel + "\",\"requiresZoneId\":" + requiresZoneId + "}";
    }

    private String uniqueSlug() {
        return "test-game-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
