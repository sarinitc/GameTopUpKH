package org.example.gametopupkh.integration;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.example.gametopupkh.auth.passwordreset.PasswordResetEmailSender;
import org.example.gametopupkh.auth.verification.VerificationCodeSender;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real migrated PostgreSQL, shared for the JVM and cleaned between sequential tests. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    // Manual singleton lifecycle avoids JUnit stopping a container that a cached Spring context still uses.
    // Ryuk removes it when this test JVM exits. No host port, production volume, or reuse is configured.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("gametopup_qa")
            .withUsername("qa")
            .withPassword("isolated-test-password");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.defer-datasource-initialization", () -> "false");
        properties.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @MockitoBean protected VerificationCodeSender verificationCodeSender;
    @MockitoBean protected PasswordResetEmailSender passwordResetEmailSender;

    @BeforeEach
    void resetIsolatedDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            // Fail before destructive cleanup if test wiring ever points outside this disposable container.
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        }
        String tables = jdbc.queryForList("""
                select tablename from pg_tables where schemaname = 'public'
                and tablename not in ('roles', 'flyway_schema_history') order by tablename
                """, String.class).stream().map(name -> "\"" + name.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(", "));
        jdbc.execute("TRUNCATE TABLE " + tables + " RESTART IDENTITY CASCADE");
    }

    protected String token(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, full_name, email, password_hash) values (?, ?, ?, ?)",
                id, "QA user", "qa-" + id + "@example.test", passwords.encode("qa-password-123"));
        jdbc.update("insert into user_roles (user_id, role_id) select ?, id from roles where name = ?", id, role);
        return jwt.generateAccessToken(users.findWithRolesById(id).orElseThrow());
    }

    protected UUID createOrder(String bearerToken) throws Exception {
        UUID packageId = createPackage();
        String body = mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("packageId", packageId,
                                "playerId", "qa-player", "zoneId", "1234"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("data").path("id").asText());
    }

    protected UUID createPackage() {
        UUID gameId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        jdbc.update("insert into games (id,name,slug) values (?, 'QA game', ?)", gameId, "qa-" + gameId);
        jdbc.update("""
                insert into game_packages (id,game_id,name,sku,diamond_amount,price,currency)
                values (?,?,'60 Diamonds',?,60,4.25,'USD')
                """, packageId, gameId, "QA-" + packageId);
        jdbc.update("insert into suppliers (id,name,status) values (?,'QA supplier','ACTIVE')", supplierId);
        jdbc.update("""
                insert into supplier_products (supplier_id,package_id,external_sku,cost_price,currency)
                values (?,?,'QA-60',?,'USD')
                """, supplierId, packageId, new BigDecimal("3.00"));
        return packageId;
    }
}
