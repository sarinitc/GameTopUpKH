package org.example.gametopupkh.identity.bootstrap;

import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.audit.entity.AuditLog;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.repository.AuditLogRepository;
import org.example.gametopupkh.auth.RefreshToken;
import org.example.gametopupkh.auth.RefreshTokenRepository;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AdminBootstrapServiceTests {

    @Autowired private AdminBootstrapService bootstrapService;
    @Autowired private AdminBootstrapProperties properties;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private PasswordEncoder passwordEncoder;

    @AfterEach
    void resetProperties() {
        properties.setEnabled(false);
        properties.setEmail("");
        properties.setConfirmation("");
    }

    @Test
    void grantsAdminToVerifiedActiveUserAndRecordsAudit() {
        User user = createUser("bootstrap-verified@example.test", null, true, false,
                AccountStatus.ACTIVE, "USER");
        RefreshToken refreshToken = createRefreshToken(user);
        enable(user.getEmail());

        assertThat(bootstrapService.provision()).isEqualTo(AdminBootstrapOutcome.GRANTED);

        User updated = users.findWithRolesById(user.getId()).orElseThrow();
        assertThat(updated.getRoleNames()).contains("USER", "ADMIN");
        assertThat(updated.getTokenVersion()).isEqualTo(1L);
        assertThat(refreshTokens.findById(refreshToken.getId()).orElseThrow().isRevoked()).isTrue();

        AuditLog audit = auditLogs.findAll().stream()
                .filter(entry -> entry.getAction() == AuditAction.ADMIN_BOOTSTRAP)
                .filter(entry -> user.getId().toString().equals(entry.getEntityId()))
                .findFirst()
                .orElseThrow();
        assertThat(audit.getActorId()).isNull();
        assertThat(audit.getActorEmail()).isEqualTo("system:admin-bootstrap");
        assertThat(audit.getActorRole()).isEqualTo("SYSTEM");
        assertThat(audit.getEntityType()).isEqualTo("users");
        assertThat(audit.getDescription()).contains("Granted ADMIN");
        assertThat(audit.getDetails())
                .contains("verified=EMAIL", "no client-supplied role")
                .doesNotContainIgnoringCase("password")
                .doesNotContain("Bearer", "accessToken", "refreshToken=");
    }

    @Test
    void grantsAdminToPhoneVerifiedUser() {
        User user = createUser("bootstrap-phone@example.test", "+85512345678", false, true,
                AccountStatus.ACTIVE, "USER");
        enable(user.getEmail());

        assertThat(bootstrapService.provision()).isEqualTo(AdminBootstrapOutcome.GRANTED);
        assertThat(users.findWithRolesById(user.getId()).orElseThrow().getRoleNames()).contains("ADMIN");
    }

    @Test
    void refusesUnverifiedAccountWithoutGrantingOrAuditing() {
        User user = createUser("bootstrap-unverified@example.test", null, false, false,
                AccountStatus.ACTIVE, "USER");
        enable(user.getEmail());

        assertThatThrownBy(bootstrapService::provision)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not completed email or phone verification");

        assertThat(users.findWithRolesById(user.getId()).orElseThrow().getRoleNames()).containsExactly("USER");
        assertThat(users.findWithRolesById(user.getId()).orElseThrow().getTokenVersion()).isZero();
        assertThat(bootstrapAuditCount(user)).isZero();
    }

    @Test
    void refusesNonActiveAccount() {
        User user = createUser("bootstrap-suspended@example.test", null, true, false,
                AccountStatus.SUSPENDED, "USER");
        enable(user.getEmail());

        assertThatThrownBy(bootstrapService::provision)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be ACTIVE");

        assertThat(users.findWithRolesById(user.getId()).orElseThrow().getRoleNames()).containsExactly("USER");
        assertThat(bootstrapAuditCount(user)).isZero();
    }

    @Test
    void isIdempotentForAnAccountThatAlreadyHasAdmin() {
        User user = createUser("bootstrap-already-admin@example.test", null, true, false,
                AccountStatus.ACTIVE, "USER", "ADMIN");
        enable(user.getEmail());

        assertThat(bootstrapService.provision()).isEqualTo(AdminBootstrapOutcome.ALREADY_ADMIN);

        User updated = users.findWithRolesById(user.getId()).orElseThrow();
        assertThat(updated.getRoleNames()).contains("USER", "ADMIN");
        assertThat(updated.getTokenVersion()).isZero();
        assertThat(bootstrapAuditCount(user)).isZero();
    }

    @Test
    void requiresTheExactConfirmationPhrase() {
        User user = createUser("bootstrap-confirmation@example.test", null, true, false,
                AccountStatus.ACTIVE, "USER");
        enable(user.getEmail());
        properties.setConfirmation("yes");

        assertThatThrownBy(bootstrapService::provision)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GRANT_ADMIN");

        assertThat(users.findWithRolesById(user.getId()).orElseThrow().getRoleNames()).containsExactly("USER");
    }

    @Test
    void refusesToRunWhenDisabled() {
        User user = createUser("bootstrap-disabled@example.test", null, true, false,
                AccountStatus.ACTIVE, "USER");
        properties.setEnabled(false);
        properties.setEmail(user.getEmail());
        properties.setConfirmation(AdminBootstrapService.CONFIRMATION_PHRASE);

        assertThat(bootstrapService.provision()).isEqualTo(AdminBootstrapOutcome.DISABLED);
        assertThat(users.findWithRolesById(user.getId()).orElseThrow().getRoleNames()).containsExactly("USER");
        assertThat(bootstrapAuditCount(user)).isZero();
    }

    private long bootstrapAuditCount(User user) {
        String entityId = user.getId().toString();
        return auditLogs.findAll().stream()
                .filter(entry -> entry.getAction() == AuditAction.ADMIN_BOOTSTRAP)
                .filter(entry -> entityId.equals(entry.getEntityId()))
                .count();
    }

    private void enable(String email) {
        properties.setEnabled(true);
        properties.setEmail(email);
        properties.setConfirmation(AdminBootstrapService.CONFIRMATION_PHRASE);
    }

    private User createUser(String email, String phoneNumber, boolean emailVerified, boolean phoneVerified,
            AccountStatus status, String... roleNames) {
        User user = new User();
        user.setFullName("Bootstrap Test User");
        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setPasswordHash(passwordEncoder.encode("Synthetic-test-password-42"));
        user.setAccountStatus(status);
        user.setEmailVerified(emailVerified);
        user.setPhoneVerified(phoneVerified);
        users.save(user);
        for (String roleName : roleNames) {
            Role role = roles.findByName(roleName).orElseThrow();
            user.addRole(role);
        }
        return users.saveAndFlush(user);
    }

    private RefreshToken createRefreshToken(User user) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(UUID.randomUUID() + "-" + UUID.randomUUID());
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        return refreshTokens.saveAndFlush(token);
    }
}
