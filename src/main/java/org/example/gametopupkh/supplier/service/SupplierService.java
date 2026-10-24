package org.example.gametopupkh.supplier.service;

import java.util.UUID;

import org.example.gametopupkh.supplier.dto.request.SupplierRequest;
import org.example.gametopupkh.supplier.dto.response.SupplierPageResponse;
import org.example.gametopupkh.supplier.dto.response.SupplierResponse;
import org.example.gametopupkh.supplier.enums.SupplierStatus;

public interface SupplierService {
    SupplierResponse create(SupplierRequest request);
    SupplierPageResponse findAll(SupplierStatus status, int page, int size);
    SupplierResponse findById(UUID id);
    SupplierResponse update(UUID id, SupplierRequest request);
    SupplierResponse updateStatus(UUID id, SupplierRequest request);
}
