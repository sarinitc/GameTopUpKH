package org.example.gametopupkh.audit.service;

import java.time.Instant;
import java.util.UUID;

import org.example.gametopupkh.audit.dto.AuditLogPageResponse;
import org.example.gametopupkh.audit.dto.AuditLogRecord;
import org.example.gametopupkh.audit.dto.AuditLogResponse;
import org.example.gametopupkh.audit.enums.AuditAction;

public interface AuditLogService {

    AuditLogPageResponse findAll(AuditAction action, UUID actorId, String actorEmail,
            String entityType, String entityId, Instant from, Instant to, int page, int size);

    AuditLogResponse findById(UUID id);

    AuditLogResponse record(AuditLogRecord record);
}
