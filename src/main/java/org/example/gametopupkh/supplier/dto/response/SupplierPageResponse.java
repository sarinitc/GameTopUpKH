package org.example.gametopupkh.supplier.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

public record SupplierPageResponse(List<SupplierResponse> content, int page, int size,
        long totalElements, int totalPages) {
    public static SupplierPageResponse from(Page<SupplierResponse> result) {
        return new SupplierPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
