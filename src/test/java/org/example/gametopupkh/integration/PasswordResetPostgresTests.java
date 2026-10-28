package org.example.gametopupkh.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.example.gametopupkh.auth.passwordreset.PasswordResetTokenCodec;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL coverage for single-use concurrency and committed session invalidation. */
class PasswordResetPostgresTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "Synthetic-test-password-42";
    private static final String NEW_PASSWORD = "Synthetic-new-password-84!";

    @Autowired private PasswordEncoder passwords;
    @Autowired private UserRepository users;
    @Autowired private PasswordResetTokenCodec codec;

    @Test
    void concurrentResetConsumesOnceAndCommitsPasswordSessionAndAuditChanges() throws Exception {
        String email = "password-reset-concurrent@example.test";
        Tokens registered = register(email);
        String resetToken = requestToken(email);

        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where token_hash = ?",
                Long.class, resetToken)).isZero();

        ExecutorService requests = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> responses = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                responses.add(requests.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent reset did not start in time");
                    }
                    return reset(resetToken, NEW_PASSWORD).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(responses.get(0).get(20, TimeUnit.SECONDS),
                    responses.get(1).get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            start.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where token_hash = ?",
                Long.class, codec.hash(resetToken))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select used_at is not null from password_reset_tokens "
                + "where token_hash = ?", Boolean.class, codec.hash(resetToken))).isTrue();

        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(passwords.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? "
                + "and revoked_at is null", Long.class, user.getId())).isZero();

        mvc.perform(get("/api/v1/orders/my").header(HttpHeaders.AUTHORIZATION, bearer(registered.access())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        auth("/refresh", Map.of("refreshToken", registered.refresh()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));

        auth("/login", Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        auth("/login", Map.of("identifier", email, "password", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        Map<String, Object> audit = jdbc.queryForMap("select action, actor_email, details from audit_logs "
                + "where action = 'PASSWORD_RESET'");
        assertThat(audit.get("action")).isEqualTo("PASSWORD_RESET");
        assertThat(audit.get("actor_email")).isEqualTo(email);
        assertThat(audit.get("details")).isNull();

        auth("/reset-password", Map.of("token", resetToken, "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_ALREADY_USED"));

        long resetAttempts = jdbc.queryForObject("select count(*) from password_reset_tokens", Long.class);
        assertThat(resetAttempts).isEqualTo(1);
    }

    @Test
    void expiredTokenCannotChangeTheCommittedPassword() throws Exception {
        String email = "password-reset-expired@example.test";
        register(email);
        String resetToken = requestToken(email);
        jdbc.update("update password_reset_tokens set expires_at = ? where token_hash = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), codec.hash(resetToken));

        reset(resetToken, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_EXPIRED"));

        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getTokenVersion()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'PASSWORD_RESET'",
                Long.class)).isZero();
    }

    private String requestToken(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").isNotEmpty());

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(passwordResetEmailSender).sendReset(eq(email), token.capture(), contains("?token="),
                any(Instant.class));
        return token.getValue();
    }

    private Tokens register(String email) throws Exception {
        MvcResult result = auth("/register", Map.of("fullName", "Password Reset QA",
                        "email", email, "password", PASSWORD))
                .andExpect(status().isCreated())
                .andReturn();
        String access = value(result, "$.data.accessToken");
        return new Tokens(access, value(result, "$.data.refreshToken"), jwt.extractUserId(access));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return auth("/reset-password", Map.of("token", token, "newPassword", newPassword));
    }

    private ResultActions auth(String endpoint, Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/auth" + endpoint).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private String value(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private record Tokens(String access, String refresh, UUID userId) {
    }
}
