package org.example.gametopupkh.player;

import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "player.verification.requests-per-minute=2")
@AutoConfigureMockMvc
@Transactional
class PlayerVerificationApiTests {
    @Autowired MockMvc mvc;
    @Autowired GameRepository games;

    @BeforeEach
    void catalog() {
        saveGame("free-fire", false);
        saveGame("mobile-legends", true);
    }

    @Test
    void requiresAuthenticationEvenUnderPublicGamesPath() throws Exception {
        mvc.perform(post("/api/v1/games/free-fire/player/verify")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"55555555\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unavailableSupplierDoesNotInventNicknameAndPreservesLeadingZeroes() throws Exception {
        mvc.perform(post("/api/v1/games/free-fire/player/verify").with(user("unavailable-ff"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\" 00555555 \"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.playerId").value("00555555"))
                .andExpect(jsonPath("$.data.nickname").isEmpty())
                .andExpect(jsonPath("$.data.provider").value("SUPPLIER_API"));
    }

    @Test
    void mobileLegendsRequiresZoneAndReturnsBothIdentifiers() throws Exception {
        mvc.perform(post("/api/v1/games/mobile-legends/player/verify").with(user("missing-zone"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"12345678\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ZONE_ID_REQUIRED"));
        mvc.perform(post("/api/v1/games/mobile-legends/player/verify").with(user("valid-zone"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"12345678\",\"zoneId\":\"0012\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.zoneId").value("0012"));
    }

    @Test
    void rejectsInvalidIdsAndUnexpectedFreeFireZone() throws Exception {
        String[] bodies = {"{}", "{\"playerId\":\"\"}", "{\"playerId\":\"   \"}",
                "{\"playerId\":\"123abc\"}", "{\"playerId\":\"１２３４\"}",
                "{\"playerId\":\"123456789012345678901\"}",
                "{\"playerId\":\"55555555\",\"zoneId\":\"1234\"}"};
        for (int i = 0; i < bodies.length; i++) {
            mvc.perform(post("/api/v1/games/free-fire/player/verify").with(user("bad-id-" + i))
                            .contentType(MediaType.APPLICATION_JSON).content(bodies[i]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsUnsupportedMissingAndInactiveGames() throws Exception {
        mvc.perform(post("/api/v1/games/unsupported/player/verify").with(user("unsupported"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"55555555\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAYER_GAME_UNSUPPORTED"));
        Game ff = games.findBySlug("free-fire").orElseThrow();
        ff.setActive(false);
        games.saveAndFlush(ff);
        mvc.perform(post("/api/v1/games/free-fire/player/verify").with(user("inactive"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"55555555\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GAME_INACTIVE"));
        games.delete(ff);
        games.flush();
        mvc.perform(post("/api/v1/games/free-fire/player/verify").with(user("missing"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"55555555\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void changingPlayerIdsAndForwardedHeadersDoesNotBypassUserRateLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/games/free-fire/player/verify").with(user("rate-limited"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"5555555" + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/games/free-fire/player/verify").with(user("rate-limited"))
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":\"77777777\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("PLAYER_VERIFICATION_RATE_LIMITED"));
    }

    private void saveGame(String slug, boolean requiresZone) {
        Game game = new Game();
        game.setSlug(slug);
        game.setName(slug);
        game.setRequiresZoneId(requiresZone);
        games.saveAndFlush(game);
    }
}
