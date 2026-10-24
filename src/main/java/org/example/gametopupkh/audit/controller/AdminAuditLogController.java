package org.example.gametopupkh.audit.controller;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.audit.dto.AuditLogPageResponse;
import org.example.gametopupkh.audit.dto.AuditLogResponse;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.service.AuditLogService;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@SecurityRequirement(name = "bearerAuth")
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    public AdminAuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AuditLogPageResponse>> findAll(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID resolvedActorId = actorId;
        String resolvedActorEmail = actorEmail;
        if (actor != null && !actor.isBlank()) {
            UUID parsedActorId = parseUserId(actor);
            if (parsedActorId != null) {
                resolvedActorId = actorId == null ? parsedActorId : actorId;
            } else if (resolvedActorEmail == null) {
                resolvedActorEmail = actor;
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(auditLogService.findAll(action, resolvedActorId,
                resolvedActorEmail, entityType, entityId, from, to, page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AuditLogResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(auditLogService.findById(id)));
    }

    private UUID parseUserId(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
