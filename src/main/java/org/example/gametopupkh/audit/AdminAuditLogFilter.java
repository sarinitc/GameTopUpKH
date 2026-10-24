package org.example.gametopupkh.audit;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.gametopupkh.audit.dto.AuditLogRecord;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.service.AuditLogService;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Appends an audit entry after every successful administrative mutation. Audit
 * failures are logged but never change the already completed API response.
 */
public class AdminAuditLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogFilter.class);
    private static final String ADMIN_PREFIX = "/api/v1/admin/";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final Set<String> SUB_RESOURCES = Set.of("status", "retry", "reconcile", "refunds");

    private final AuditLogService auditLogService;
    private final UserRepository users;

    public AdminAuditLogFilter(AuditLogService auditLogService, UserRepository users) {
        this.auditLogService = auditLogService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);

        String path = requestPath(request);
        AuditAction action = actionOf(request.getMethod(), path);
        if (action == null || !path.startsWith(ADMIN_PREFIX)
                || response.getStatus() < 200 || response.getStatus() >= 300) {
            return;
        }

        try {
            auditLogService.record(entry(request, path, action, response.getStatus()));
        } catch (RuntimeException e) {
            log.warn("Could not persist audit log for {} {}", request.getMethod(), path, e);
        }
    }

    private AuditLogRecord entry(HttpServletRequest request, String path, AuditAction action, int status) {
        String[] segments = path.substring(ADMIN_PREFIX.length()).split("/");
        String entityType = segments.length == 0 || segments[0].isBlank() ? null : segments[0];
        String entityId = segments.length > 1 && !SUB_RESOURCES.contains(segments[1]) ? segments[1] : null;
        Actor actor = currentActor();
        String description = truncate(request.getMethod() + " " + path + " -> " + status, 500);

        return new AuditLogRecord(action, actor.id(), actor.email(), actor.role(), truncate(entityType, 60),
                truncate(entityId, 100), description, null, truncate(request.getRemoteAddr(), 45));
    }

    private AuditAction actionOf(String method, String path) {
        return switch (method) {
            case "POST" -> path.endsWith("/retry") ? AuditAction.RETRY
                    : path.endsWith("/reconcile") ? AuditAction.RECONCILE
                    : path.endsWith("/refunds") ? AuditAction.REFUND
                    : AuditAction.CREATE;
            case "PUT" -> AuditAction.UPDATE;
            case "PATCH" -> path.endsWith("/status") ? AuditAction.STATUS_CHANGE : AuditAction.UPDATE;
            case "DELETE" -> AuditAction.DELETE;
            default -> null;
        };
    }

    private Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Actor.NONE;
        }
        UUID actorId = parseUserId(authentication.getName());
        String email = actorId == null ? null : users.findById(actorId).map(User::getEmail).orElse(null);
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .findFirst()
                .orElse(null);
        return new Actor(actorId, email, role);
    }

    private UUID parseUserId(String username) {
        try {
            return UUID.fromString(username);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record Actor(UUID id, String email, String role) {
        private static final Actor NONE = new Actor(null, null, null);
    }
}
