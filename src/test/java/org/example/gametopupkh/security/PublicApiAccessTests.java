package org.example.gametopupkh.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the anonymous API surface down: the storefront catalog is readable
 * without a JWT, while customer, payment, player and admin routes are not.
 *
 * <p>Catalog reads deliberately assert "not 401" instead of a specific payload;
 * an unknown id reaching the controller (404) proves Security let the request
 * through without depending on seeded data.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicApiAccessTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousCallersCanReadThePublicCatalog() throws Exception {
        mvc.perform(get("/api/v1/games"))
                .andExpect(status().isOk());

        // 404 rather than 401/403 proves the request passed the security chain.
        mvc.perform(get("/api/v1/games/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousCallersCanReadPublicPackages() throws Exception {
        mvc.perform(get("/api/v1/games/" + UUID.randomUUID() + "/packages"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/packages/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/orders/my",
            "/api/v1/admin/suppliers",
            "/api/v1/admin/dashboard/summary",
            "/api/v1/payments/00000000-0000-0000-0000-000000000000"
    })
    void protectedReadsRejectAnonymousCallers(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedWritesRejectAnonymousCallers() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/v1/payments/00000000-0000-0000-0000-000000000000/verify"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void playerVerificationRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/games/mobile-legends/player/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SUPPORT"})
    void nonAdminRolesCannotReachAdminApis(String role) throws Exception {
        mvc.perform(get("/api/v1/admin/suppliers").with(user("reader").roles(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousCallersCanReadSwaggerInLocalDevelopment() throws Exception {
        // Default profile (dev/test): app.openapi.public=true.
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
