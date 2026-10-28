package org.example.gametopupkh.integration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
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
import java.util.concurrent.locks.LockSupport;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises real JWT authentication and committed PostgreSQL state, without printing credentials. */
class AuthSecurityPostgresTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "Synthetic-test-password-42";

    @Autowired private PasswordEncoder passwords;

    @Test
    void registrationPersistsOnlyPasswordAndRefreshTokenHashesAndNormalizesEmail() throws Exception {
        Tokens registered = register("MixedCase@Example.com");

        Map<String, Object> persisted = jdbc.queryForMap(
                "select email, password_hash, account_status from users where id = ?", registered.userId());
        assertThat(persisted.get("email")).isEqualTo("mixedcase@example.com");
        assertThat(persisted.get("account_status")).isEqualTo("ACTIVE");
        assertThat(passwords.matches(PASSWORD, (String) persisted.get("password_hash"))).isTrue();
        assertThat(PASSWORD.equals(persisted.get("password_hash"))).isFalse();
        assertThat(jdbc.queryForObject("select token_hash from refresh_tokens where user_id = ?", String.class,
                registered.userId()).equals(jwt.hashRefreshToken(registered.refresh()))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Long.class,
                registered.refresh())).isZero();
        assertThat(jdbc.queryForList("select r.name from roles r join user_roles ur on ur.role_id = r.id "
                + "where ur.user_id = ?", String.class, registered.userId())).containsExactly("USER");

        auth("/register", Map.of("fullName", "Duplicate", "email", "MIXEDCASE@example.com",
                        "password", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(1);
    }

    @Test
    void loginRefreshAndLogoutCommitRotationWithoutStoringRawTokens() throws Exception {
        Tokens registered = register("lifecycle@example.com");
        MvcResult login = auth("/login", Map.of("identifier", "LIFECYCLE@example.com", "password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        String refresh = value(login, "$.data.refreshToken");

        MvcResult rotated = auth("/refresh", Map.of("refreshToken", refresh))
                .andExpect(status().isOk()).andReturn();
        String nextRefresh = value(rotated, "$.data.refreshToken");
        String nextAccess = value(rotated, "$.data.accessToken");
        assertThat(refresh.equals(nextRefresh)).isFalse();
        assertThat(jdbc.queryForObject("select revoked_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class, jwt.hashRefreshToken(refresh))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Long.class,
                registered.userId())).isEqualTo(3);

        auth("/refresh", Map.of("refreshToken", refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
        mvc.perform(get("/api/v1/orders/my").header(HttpHeaders.AUTHORIZATION, bearer(nextAccess)))
                .andExpect(status().isOk());

        auth("/logout", Map.of("refreshToken", nextRefresh)).andExpect(status().isOk());
        auth("/logout", Map.of("refreshToken", nextRefresh)).andExpect(status().isOk());
        auth("/refresh", Map.of("refreshToken", nextRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Long.class,
                registered.userId())).isEqualTo(3);
    }

    @Test
    void expiredRefreshTokenCannotCreateAnotherSession() throws Exception {
        Tokens registered = register("expired@example.com");
        jdbc.update("update refresh_tokens set expires_at = ? where user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), registered.userId());

        auth("/refresh", Map.of("refreshToken", registered.refresh()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EXPIRED_REFRESH_TOKEN"));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshReuseCreatesExactlyOneSuccessor() throws Exception {
        Tokens registered = register("concurrent-refresh@example.com");
        ExecutorService requests = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> responses = new ArrayList<>();

        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "select id from refresh_tokens where token_hash = ? for update")) {
                statement.setString(1, jwt.hashRefreshToken(registered.refresh()));
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }

            for (int index = 0; index < 2; index++) {
                responses.add(requests.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent refresh did not start in time");
                    }
                    return auth("/refresh", Map.of("refreshToken", registered.refresh()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            // Observe both real transactions at PostgreSQL's lock boundary. Unlocked reads can both
            // see an unrevoked token; a locking read serializes before either decides to rotate it.
            awaitTwoRefreshLockWaiters();
            blocker.commit();

            List<Integer> statuses = List.of(responses.get(0).get(20, TimeUnit.SECONDS),
                    responses.get(1).get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            start.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?",
                Long.class, registered.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Long.class, registered.userId())).isEqualTo(1);
        auth("/refresh", Map.of("refreshToken", registered.refresh()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "DISABLED", "PENDING_VERIFICATION"})
    void accountStatusChangesInvalidateExistingAccessAndBlockLoginAndRefresh(String accountStatus) throws Exception {
        Tokens registered = register("account-state@example.com");
        jdbc.update("update users set account_status = ? where id = ?", accountStatus, registered.userId());

        mvc.perform(get("/api/v1/orders/my").header(HttpHeaders.AUTHORIZATION, bearer(registered.access())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        auth("/login", Map.of("identifier", "account-state@example.com", "password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        auth("/refresh", Map.of("refreshToken", registered.refresh()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(1);
    }

    @Test
    void removingAdminRoleTakesEffectForAnAlreadyIssuedAccessToken() throws Exception {
        String admin = token("ADMIN");
        mvc.perform(get("/api/v1/admin/suppliers").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        jdbc.update("delete from user_roles where user_id = ? and role_id = (select id from roles where name = 'ADMIN')",
                jwt.extractUserId(admin));

        mvc.perform(get("/api/v1/admin/suppliers").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidCredentialsAndMalformedBearerTokenHaveSafeApiErrorsAndCreateNoSession() throws Exception {
        register("credentials@example.com");
        long sessionsBefore = jdbc.queryForObject("select count(*) from refresh_tokens", Long.class);
        for (String identifier : List.of("credentials@example.com", "missing@example.com")) {
            MvcResult result = auth("/login", Map.of("identifier", identifier, "password", "Wrong-synthetic-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Wrong-synthetic-password", "password_hash");
        }
        mvc.perform(get("/api/v1/orders/my").header(HttpHeaders.AUTHORIZATION, "Bearer synthetic-invalid-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(sessionsBefore);
    }

    @Test
    void anonymousCustomerAndSupportMutationsNeverPersistSupplierOrSuccessAudit() throws Exception {
        String body = json.writeValueAsString(Map.of("name", "Unauthorized supplier"));
        mvc.perform(post("/api/v1/admin/suppliers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        for (String role : List.of("USER", "SUPPORT")) {
            mvc.perform(post("/api/v1/admin/suppliers").header(HttpHeaders.AUTHORIZATION, bearer(token(role)))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(jdbc.queryForObject("select count(*) from suppliers", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Long.class)).isZero();
    }

    @Test
    void successfulAdminAuditHasRealActorButNoRequestBodyTokenHeaderOrQuerySecrets() throws Exception {
        String admin = token("ADMIN");
        String syntheticSecret = "synthetic-api-key-never-store-this";
        String bodyValue = "Synthetic confidential body";
        mvc.perform(post("/api/v1/admin/suppliers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .header("X-Api-Key", syntheticSecret)
                        .queryParam("api_key", syntheticSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", bodyValue))))
                .andExpect(status().isCreated());

        Map<String, Object> audit = jdbc.queryForMap(
                "select action, actor_id, actor_email, actor_role, entity_type, description, details from audit_logs");
        assertThat(audit.get("action")).isEqualTo("CREATE");
        assertThat(audit.get("actor_id")).isEqualTo(jwt.extractUserId(admin));
        assertThat(audit.get("actor_email")).isEqualTo(jdbc.queryForObject(
                "select email from users where id = ?", String.class, jwt.extractUserId(admin)));
        assertThat(audit.get("actor_role")).isEqualTo("ADMIN");
        assertThat(audit.get("entity_type")).isEqualTo("suppliers");
        assertThat(audit.get("description")).isEqualTo("POST /api/v1/admin/suppliers -> 201");
        assertThat(audit.get("details")).isNull();
        String persistedAudit = json.writeValueAsString(audit);
        assertThat(persistedAudit.contains(admin)).isFalse();
        assertThat(persistedAudit).doesNotContain(syntheticSecret, bodyValue, "Authorization", "X-Api-Key");
    }

    @Test
    void invalidAdminPayloadDoesNotProduceSuccessAuditOrSupplier() throws Exception {
        mvc.perform(post("/api/v1/admin/suppliers").header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from suppliers", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Long.class)).isZero();
    }

    @Test
    void orderOwnershipIsEnforcedWithRealBearerTokensAndAdminReadAccess() throws Exception {
        String owner = token("USER");
        String stranger = token("USER");
        String support = token("SUPPORT");
        UUID packageId = packageFixture();
        String ownerOrder = createOrder(owner, packageId, "owner-player");
        createOrder(stranger, packageId, "other-player");

        mvc.perform(get("/api/v1/orders/my").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(ownerOrder));
        mvc.perform(get("/api/v1/orders/" + ownerOrder).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk());
        for (String nonOwner : List.of(stranger, support)) {
            mvc.perform(get("/api/v1/orders/" + ownerOrder).header(HttpHeaders.AUTHORIZATION, bearer(nonOwner)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/orders/" + ownerOrder))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/orders/" + ownerOrder).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/orders/" + ownerOrder).header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playerId").value("owner-player"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Long.class)).isZero();
    }

    private Tokens register(String email) throws Exception {
        MvcResult result = auth("/register", Map.of("fullName", "Security Test Customer", "email", email,
                        "password", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.password").doesNotExist())
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andReturn();
        String access = value(result, "$.data.accessToken");
        return new Tokens(access, value(result, "$.data.refreshToken"), jwt.extractUserId(access));
    }

    private ResultActions auth(String endpoint, Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/auth" + endpoint).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private UUID packageFixture() {
        UUID gameId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        jdbc.update("insert into games (id, name, slug) values (?, ?, ?)", gameId, "Ownership Test Game",
                "ownership-" + gameId);
        jdbc.update("insert into game_packages (id, game_id, name, sku, diamond_amount, price, currency) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", packageId, gameId, "Ownership Test Package",
                "OWNERSHIP-" + packageId, 60, new BigDecimal("3.25"), "USD");
        return packageId;
    }

    private String createOrder(String access, UUID packageId, String playerId) throws Exception {
        return value(mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("packageId", packageId, "playerId", playerId))))
                .andExpect(status().isCreated()).andReturn(), "$.data.id");
    }

    private String value(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private String bearer(String access) {
        return "Bearer " + access;
    }

    private void awaitTwoRefreshLockWaiters() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiters;
        do {
            waiters = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and state = 'active'
                      and wait_event_type = 'Lock' and query ilike '%refresh_tokens%'
                    """, Integer.class);
            if (waiters >= 2) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        } while (System.nanoTime() < deadline);
        assertThat(waiters).as("both refresh transactions must reach the database lock before release")
                .isGreaterThanOrEqualTo(2);
    }

    // Intentionally has no record-generated toString(): assertion diagnostics must not reveal tokens.
    private static final class Tokens {
        private final String access;
        private final String refresh;
        private final UUID userId;

        private Tokens(String access, String refresh, UUID userId) {
            this.access = access;
            this.refresh = refresh;
            this.userId = userId;
        }

        private String access() { return access; }
        private String refresh() { return refresh; }
        private UUID userId() { return userId; }
    }
}
