package org.example.gametopupkh.audit.dto;

import java.util.UUID;

import org.example.gametopupkh.audit.enums.AuditAction;

/**
 * Details required to append an audit entry. It is used internally by the
 * administrative request recorder rather than exposed as a public endpoint.
 */
public record AuditLogRecord(
        AuditAction action,
        UUID actorId,
        String actorEmail,
        String actorRole,
        String entityType,
        String entityId,
        String description,
        String details,
        String ipAddress) {
}
