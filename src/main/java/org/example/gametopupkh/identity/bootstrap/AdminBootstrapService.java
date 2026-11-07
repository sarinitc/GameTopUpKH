package org.example.gametopupkh.identity.bootstrap;

import java.time.Instant;
import java.util.Locale;

import org.example.gametopupkh.audit.dto.AuditLogRecord;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.service.AuditLogService;
import org.example.gametopupkh.auth.RefreshTokenRepository;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time, server-side provisioning of the first ADMIN account.
 *
 * <p>There is deliberately no controller for this operation. It runs only when
 * {@code app.admin-bootstrap.enabled=true}, targets exactly one existing account
 * selected by email, refuses accounts that are not active or have not completed
 * email/phone verification, and writes an append-only audit record. The grant
 * increments the user's token version and revokes refresh tokens so a fresh
 * login is required to receive the ADMIN authority.</p>
 */
@Service
public class AdminBootstrapService {

    /** Exact phrase required in {@code app.admin-bootstrap.confirmation}. */
    public static final String CONFIRMATION_PHRASE = "GRANT_ADMIN";

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String AUDIT_ACTOR = "system:admin-bootstrap";
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final AdminBootstrapProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLogService auditLogs;

    public AdminBootstrapService(AdminBootstrapProperties properties, UserRepository users,
            RoleRepository roles, RefreshTokenRepository refreshTokens, AuditLogService auditLogs) {
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.auditLogs = auditLogs;
    }

    @Transactional
    public AdminBootstrapOutcome provision() {
        if (!properties.isEnabled()) {
            return AdminBootstrapOutcome.DISABLED;
        }
        String email = normalizeEmail(properties.getEmail());
        if (email == null) {
            throw new IllegalStateException(
                    "app.admin-bootstrap.email must name the existing account that should receive ADMIN");
        }
        if (!CONFIRMATION_PHRASE.equals(properties.getConfirmation().trim())) {
            throw new IllegalStateException("app.admin-bootstrap.confirmation must be exactly "
                    + CONFIRMATION_PHRASE);
        }

        // Lock the selected row so two bootstrap runs cannot grant the role twice.
        User user = users.findForVerificationByEmail(email)
                .orElseThrow(() -> new IllegalStateException(
                        "No account exists for app.admin-bootstrap.email; register the account first"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new IllegalStateException("The selected account must be ACTIVE before it can receive ADMIN");
        }

        boolean emailVerified = user.isEmailVerified();
        boolean phoneVerified = user.isPhoneVerified();
        if (!emailVerified && !phoneVerified) {
            throw new IllegalStateException(
                    "The selected account has not completed email or phone verification; refusing to grant ADMIN");
        }
        if (user.getRoleNames().contains(ADMIN_ROLE)) {
            log.info("One-time ADMIN bootstrap skipped: userId={} already has ADMIN", user.getId());
            return AdminBootstrapOutcome.ALREADY_ADMIN;
        }

        Role adminRole = roles.findByName(ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException("The ADMIN role is not seeded"));
        long previousTokenVersion = user.getTokenVersion();
        long nextTokenVersion = previousTokenVersion + 1;
        user.addRole(adminRole);
        user.setTokenVersion(nextTokenVersion);
        users.saveAndFlush(user);

        int revokedTokens = refreshTokens.revokeAllForUser(user.getId(), Instant.now());
        String verification = emailVerified
                ? (phoneVerified ? "EMAIL+PHONE" : "EMAIL")
                : "PHONE";
        auditLogs.record(new AuditLogRecord(
                AuditAction.ADMIN_BOOTSTRAP,
                null,
                AUDIT_ACTOR,
                "SYSTEM",
                "users",
                user.getId().toString(),
                "Granted ADMIN through one-time server-side bootstrap",
                "verified=" + verification
                        + "; tokenVersion=" + previousTokenVersion + "->" + nextTokenVersion
                        + "; refreshTokensRevoked=" + revokedTokens
                        + "; no client-supplied role",
                null));

        log.info("One-time ADMIN bootstrap granted ADMIN to userId={}; tokenVersion {} -> {}; "
                        + "refreshTokensRevoked={}",
                user.getId(), previousTokenVersion, nextTokenVersion, revokedTokens);
        return AdminBootstrapOutcome.GRANTED;
    }

    private String normalizeEmail(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
