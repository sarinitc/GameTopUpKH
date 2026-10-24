package org.example.gametopupkh.audit.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;
import org.example.gametopupkh.audit.dto.AuditLogPageResponse;
import org.example.gametopupkh.audit.dto.AuditLogRecord;
import org.example.gametopupkh.audit.dto.AuditLogResponse;
import org.example.gametopupkh.audit.entity.AuditLog;
import org.example.gametopupkh.audit.enums.AuditAction;
import org.example.gametopupkh.audit.repository.AuditLogRepository;
import org.example.gametopupkh.audit.service.AuditLogService;
import org.example.gametopupkh.common.exception.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditLogServiceImpl implements AuditLogService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogs;

    public AuditLogServiceImpl(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Override
    public AuditLogPageResponse findAll(AuditAction action, UUID actorId, String actorEmail,
            String entityType, String entityId, Instant from, Instant to, int page, int size) {
        validatePagination(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "The from timestamp must not be after the to timestamp");
        }

        Specification<AuditLog> specification = specification(action, actorId, normalize(actorEmail),
                normalize(entityType), normalize(entityId), from, to);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<AuditLogResponse> result = auditLogs.findAll(specification, pageable)
                .map(AuditLogResponse::from);
        return AuditLogPageResponse.from(result);
    }

    @Override
    public AuditLogResponse findById(UUID id) {
        return AuditLogResponse.from(auditLogs.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "AUDIT_LOG_NOT_FOUND", "Audit log " + id + " was not found")));
    }

    @Override
    @Transactional
    public AuditLogResponse record(AuditLogRecord entry) {
        AuditLog log = new AuditLog();
        log.setAction(Objects.requireNonNull(entry.action(), "action"));
        log.setActorId(entry.actorId());
        log.setActorEmail(normalize(entry.actorEmail()));
        log.setActorRole(normalize(entry.actorRole()));
        log.setEntityType(normalize(entry.entityType()));
        log.setEntityId(normalize(entry.entityId()));
        log.setDescription(normalize(entry.description()));
        log.setDetails(normalize(entry.details()));
        log.setIpAddress(normalize(entry.ipAddress()));
        return AuditLogResponse.from(auditLogs.saveAndFlush(log));
    }

    private Specification<AuditLog> specification(AuditAction action, UUID actorId, String actorEmail,
            String entityType, String entityId, Instant from, Instant to) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (action != null) {
                predicates.add(builder.equal(root.get("action"), action));
            }
            if (actorId != null) {
                predicates.add(builder.equal(root.get("actorId"), actorId));
            }
            if (actorEmail != null) {
                predicates.add(builder.equal(builder.lower(root.get("actorEmail")),
                        actorEmail.toLowerCase(Locale.ROOT)));
            }
            if (entityType != null) {
                predicates.add(builder.equal(builder.lower(root.get("entityType")),
                        entityType.toLowerCase(Locale.ROOT)));
            }
            if (entityId != null) {
                predicates.add(builder.equal(root.get("entityId"), entityId));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Page must be nonnegative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Requested page exceeds the supported offset");
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
