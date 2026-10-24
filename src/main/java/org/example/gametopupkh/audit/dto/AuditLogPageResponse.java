package org.example.gametopupkh.audit.dto;

import java.util.List;

import org.springframework.data.domain.Page;

public record AuditLogPageResponse(List<AuditLogResponse> content, int page, int size,
        long totalElements, int totalPages) {

    public static AuditLogPageResponse from(Page<AuditLogResponse> result) {
        return new AuditLogPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
