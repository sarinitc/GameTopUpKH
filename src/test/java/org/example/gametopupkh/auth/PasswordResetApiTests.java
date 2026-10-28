package org.example.gametopupkh.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.example.gametopupkh.audit.entity.AuditLog;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.repository.AuditLogRepository;
import org.example.gametopupkh.auth.passwordreset.PasswordResetEmailSender;
import org.example.gametopupkh.auth.passwordreset.PasswordResetServiceImpl;
import org.example.gametopupkh.auth.passwordreset.PasswordResetTokenCodec;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.security.AuthenticatedUser;
import org.example.gametopupkh.security.CustomUserDetailsService;
import org.example.gametopupkh.security.JwtService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fast H2 coverage for the password reset workflow, with email delivery mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordResetApiTests {

    private static final String PASSWORD = "secret123";
    private static final String NEW_PASSWORD = "NewStrongPassword123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PasswordResetTokenCodec codec;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JwtService jwtService;
    @Autowired private CustomUserDetailsService userDetailsService;
    @Autowired private EntityManager entityManager;
    @MockitoBean private PasswordResetEmailSender emailSender;

    @Test
    void registeredEmailReceivesGenericResponseAndADeliveryWithoutExposingTheSecret() throws Exception {
        String email = uniqueEmail();
        register(email);

        MvcResult result = forgot(email, uniqueIp()).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value(PasswordResetServiceImpl.FORGOT_PASSWORD_MESSAGE))
                .andReturn();

        ArgumentCapture captured = captureToken(email);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(captured.token());
        assertThat(captured.token()).isNotBlank();
        verify(emailSender).sendReset(eq(email), eq(captured.token()),
                org.mockito.ArgumentMatchers.contains("?token="), any(Instant.class));
    }

    @Test
    void unknownEmailReturnsTheSameGenericResponseAndNoEmail() throws Exception {
        String knownEmail = uniqueEmail();
        register(knownEmail);

        MvcResult known = forgot(knownEmail, uniqueIp()).andExpect(status().isOk()).andReturn();
        MvcResult unknown = forgot(uniqueEmail(), uniqueIp()).andExpect(status().isOk()).andReturn();

        assertThat(value(unknown, "$.data.message")).isEqualTo(value(known, "$.data.message"));
        verify(emailSender, times(1)).sendReset(anyString(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void invalidEmailFormatIsRejectedBeforeAnyWork() throws Exception {
        forgotRaw("not-an-email", uniqueIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(emailSender, times(0)).sendReset(anyString(), anyString(), anyString(), any(Instant.class));
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Long.class)).isZero();
    }

    @Test
    void validTokenResetsPasswordHashesItAndAllowsLoginOnlyWithTheNewPassword() throws Exception {
        String email = uniqueEmail();
        Tokens tokens = registerWithTokens(email);
        String resetToken = requestResetToken(email);

        reset(resetToken, NEW_PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value(PasswordResetServiceImpl.PASSWORD_RESET_MESSAGE));

        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(1);
        entityManager.flush();
        assertThat(jwtService.extractTokenVersion(tokens.access())).isZero();
        AuthenticatedUser principal = (AuthenticatedUser) userDetailsService
                .loadUserByUsername(tokens.userId().toString());
        assertThat(principal.getTokenVersion()).isEqualTo(1);

        login(email, PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login(email, NEW_PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value(email));

        mockMvc.perform(get("/api/v1/orders/my").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        refresh(tokens.refresh()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        reset("not-a-real-token", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = requestResetToken(email);
        entityManager.flush();
        jdbc.update("update password_reset_tokens set expires_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), codec.hash(token));
        entityManager.clear();

        reset(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_EXPIRED"));
    }

    @Test
    void usedTokenCannotBeReplayed() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = requestResetToken(email);

        reset(token, NEW_PASSWORD).andExpect(status().isOk());
        reset(token, "AnotherStrongPassword123!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_ALREADY_USED"));

        assertThat(jdbc.queryForObject("select used_at is not null from password_reset_tokens "
                + "where token_hash = ?", Boolean.class, codec.hash(token))).isTrue();
    }

    @Test
    void passwordPolicyIsEnforcedBeforeTheTokenIsConsumed() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = requestResetToken(email);

        reset(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        entityManager.flush();
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where used_at is null "
                + "and token_hash = ?", Long.class, codec.hash(token))).isEqualTo(1);
    }

    @Test
    void aNewRequestRetiresThePreviousCredential() throws Exception {
        String email = uniqueEmail();
        register(email);
        String first = requestResetToken(email);
        entityManager.flush();
        jdbc.update("update password_reset_tokens set created_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), codec.hash(first));
        entityManager.clear();

        String second = requestResetToken(email);
        assertThat(second).isNotEqualTo(first);
        reset(first, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_ALREADY_USED"));
        reset(second, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void emailRateLimitAppliesEquallyToUnknownAndRegisteredAddresses() throws Exception {
        String email = uniqueEmail();
        String ip = uniqueIp();
        for (int attempt = 0; attempt < 3; attempt++) {
            forgot(email, ip).andExpect(status().isOk());
        }
        forgot(email, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_RATE_LIMITED"));
    }

    @Test
    void emailDeliveryFailureDoesNotChangeTheGenericResponse() throws Exception {
        String email = uniqueEmail();
        register(email);
        doThrow(new IllegalStateException("synthetic SMTP outage"))
                .when(emailSender).sendReset(anyString(), anyString(), anyString(), any(Instant.class));

        MvcResult result = forgot(email, uniqueIp()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value(PasswordResetServiceImpl.FORGOT_PASSWORD_MESSAGE))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("synthetic SMTP outage", "password_reset_tokens");
    }

    @Test
    void unavailableDeliveryFailsClosedWithoutStoringACredential() throws Exception {
        String email = uniqueEmail();
        register(email);
        doThrow(new IllegalStateException("delivery not configured"))
                .when(emailSender).checkAvailability();

        forgot(email, uniqueIp()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value(PasswordResetServiceImpl.FORGOT_PASSWORD_MESSAGE));

        verify(emailSender, times(0)).sendReset(anyString(), anyString(), anyString(), any(Instant.class));
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Long.class)).isZero();
    }

    @Test
    void resetIsAuditedWithoutTheSecretAndNothingIsLogged() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = requestResetToken(email);

        Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MvcResult result = reset(token, NEW_PASSWORD).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        } finally {
            logger.detachAppender(appender);
        }

        List<AuditLog> entries = auditLogs.findAll();
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.PASSWORD_RESET);
            assertThat(entry.getActorEmail()).isEqualTo(email);
            assertThat(entry.getDetails()).isNull();
            assertThat(entry.getDescription()).isEqualTo("Password reset completed");
        });
        assertThat(entries).allSatisfy(entry -> assertThat(String.valueOf(entry.getDetails())).doesNotContain(token));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(token));
    }

    private ArgumentCapture captureToken(String email) {
        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSender, org.mockito.Mockito.atLeastOnce())
                .sendReset(eq(email), token.capture(), anyString(), any(Instant.class));
        return new ArgumentCapture(token.getAllValues().get(token.getAllValues().size() - 1));
    }

    private String requestResetToken(String email) throws Exception {
        forgot(email, uniqueIp()).andExpect(status().isOk());
        return captureToken(email).token();
    }

    private ResultActions forgot(String email, String ip) throws Exception {
        return forgotRaw(email, ip);
    }

    private ResultActions forgotRaw(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/forgot-password")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "newPassword", newPassword))));
    }

    @Test
    void requestNormalizesEmailConsistentlyWithRegistration() throws Exception {
        String email = uniqueEmail();
        register(email);

        forgot(email.toUpperCase(java.util.Locale.ROOT), uniqueIp()).andExpect(status().isOk());

        verify(emailSender).sendReset(eq(email), anyString(), anyString(), any(Instant.class));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("identifier", email, "password", password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private void register(String email) throws Exception {
        registerWithTokens(email);
    }

    private Tokens registerWithTokens(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Reset Test Customer",
                                "email", email, "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        return new Tokens(value(result, "$.data.accessToken"), value(result, "$.data.refreshToken"),
                jwtService.extractUserId(value(result, "$.data.accessToken")));
    }

    private String value(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private String uniqueEmail() {
        return "reset-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueIp() {
        return "ip-" + UUID.randomUUID();
    }

    private record Tokens(String access, String refresh, UUID userId) {
    }

    private record ArgumentCapture(String token) {
    }
}
