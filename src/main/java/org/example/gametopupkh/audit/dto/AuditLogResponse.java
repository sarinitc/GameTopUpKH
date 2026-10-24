package org.example.gametopupkh.audit.dto;

import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.audit.entity.AuditLog;
import org.example.gametopupkh.audit.enums.AuditAction;

public record AuditLogResponse(
        UUID id,
        AuditAction action,
        UUID actorId,
        String actorEmail,
        String actorRole,
        String entityType,
        String entityId,
        String description,
        String details,
        String ipAddress,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getAction(), log.getActorId(), log.getActorEmail(),
                log.getActorRole(), log.getEntityType(), log.getEntityId(), log.getDescription(),
                log.getDetails(), log.getIpAddress(), log.getCreatedAt());
    }
}
