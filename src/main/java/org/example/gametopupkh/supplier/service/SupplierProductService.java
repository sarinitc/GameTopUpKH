package org.example.gametopupkh.supplier.service;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.supplier.dto.request.SupplierProductRequest;
import org.example.gametopupkh.supplier.dto.response.SupplierProductResponse;

public interface SupplierProductService {
    SupplierProductResponse create(SupplierProductRequest request);
    List<SupplierProductResponse> findAll(UUID supplierId, UUID packageId, Boolean active);
    SupplierProductResponse findById(UUID id);
    SupplierProductResponse update(UUID id, SupplierProductRequest request);
    SupplierProductResponse updateStatus(UUID id, SupplierProductRequest request);
}
