package org.example.gametopupkh.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production default: Springdoc is disabled, and the docs paths are not public.
 * An anonymous caller is rejected by Spring Security before it can learn whether
 * the endpoint exists, and even an ADMIN only sees a 404 because the endpoint is
 * not registered.
 */
@SpringBootTest(properties = {
        "app.openapi.public=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
class SwaggerUiDisabledTests {

    private static final String[] SWAGGER_PATHS = {
            "/v3/api-docs",
            "/swagger-ui.html",
            "/swagger-ui/index.html"
    };

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousCallersCannotProbeDisabledSwagger() throws Exception {
        for (String path : SWAGGER_PATHS) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void evenAdminSeesNotFoundWhenSpringdocIsDisabled() throws Exception {
        for (String path : SWAGGER_PATHS) {
            mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HTTP_404"));
        }
    }
}
