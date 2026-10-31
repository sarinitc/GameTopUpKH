package org.example.gametopupkh.security;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser CORS behaviour for the storefront origin.
 *
 * <p>The allowlist is exact, so an unknown origin is rejected even for public
 * endpoints, and a preflight never bypasses authentication.</p>
 */
@SpringBootTest(properties = "APP_CORS_ALLOWED_ORIGINS=https://gametopup-kh.vercel.app")
@AutoConfigureMockMvc
class CorsConfigurationTests {

    private static final String ALLOWED_ORIGIN = "https://gametopup-kh.vercel.app";
    private static final String UNKNOWN_ORIGIN = "https://attacker.example.com";

    @Autowired
    private MockMvc mvc;

    @Test
    void preflightFromTheAllowedOriginIsAcceptedWithBearerRequestHeaders() throws Exception {
        // Asking for authorization and content-type is part of the preflight: Spring
        // answers 403 when a requested header is not on the allowlist, so a 200 proves
        // both are allowed.
        mvc.perform(options("/api/v1/orders/my")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        Matchers.containsStringIgnoringCase("authorization")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void preflightFromAnUnknownOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/orders/my")
                        .header(HttpHeaders.ORIGIN, UNKNOWN_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void allowedOriginCanCallAPublicEndpoint() throws Exception {
        mvc.perform(get("/api/v1/games").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void unknownOriginCannotCallAPublicEndpoint() throws Exception {
        mvc.perform(get("/api/v1/games").header(HttpHeaders.ORIGIN, UNKNOWN_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void corsDoesNotBypassAuthenticationOnProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/v1/orders/my").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void preflightHeadersAreCacheableButShortLived() throws Exception {
        mvc.perform(options("/api/v1/games")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
    }
}
