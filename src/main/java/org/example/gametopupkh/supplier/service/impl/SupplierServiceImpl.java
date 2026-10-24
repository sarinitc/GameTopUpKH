package org.example.gametopupkh.supplier.service.impl;

import java.util.UUID;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.supplier.dto.request.SupplierRequest;
import org.example.gametopupkh.supplier.dto.response.SupplierPageResponse;
import org.example.gametopupkh.supplier.dto.response.SupplierResponse;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.repository.SupplierRepository;
import org.example.gametopupkh.supplier.service.SupplierService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SupplierServiceImpl implements SupplierService {
    private final SupplierRepository suppliers;

    public SupplierServiceImpl(SupplierRepository suppliers) { this.suppliers = suppliers; }

    @Override
    public SupplierResponse create(SupplierRequest request) {
        Supplier supplier = new Supplier();
        supplier.setName(request.name().trim());
        supplier.setStatus(request.status() == null ? SupplierStatus.INACTIVE : request.status());
        return SupplierResponse.from(suppliers.saveAndFlush(supplier));
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierPageResponse findAll(SupplierStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Page must be nonnegative and size must be between 1 and 100");
        }
        // JPA's first-result offset is an int even though Pageable exposes a long.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Requested page exceeds the supported offset");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Supplier> result = status == null ? suppliers.findAll(pageable)
                : suppliers.findAllByStatus(status, pageable);
        return SupplierPageResponse.from(result.map(SupplierResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierResponse findById(UUID id) {
        return SupplierResponse.from(suppliers.findById(id).orElseThrow(() -> missing(id)));
    }

    @Override
    public SupplierResponse update(UUID id, SupplierRequest request) {
        Supplier supplier = locked(id);
        if (request.status() != null && request.status() != supplier.getStatus()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SUPPLIER_STATUS_SEPARATE",
                    "Use the status endpoint to change supplier status");
        }
        supplier.setName(request.name().trim());
        return SupplierResponse.from(suppliers.saveAndFlush(supplier));
    }

    @Override
    public SupplierResponse updateStatus(UUID id, SupplierRequest request) {
        Supplier supplier = locked(id);
        supplier.setStatus(request.status());
        return SupplierResponse.from(suppliers.saveAndFlush(supplier));
    }

    private Supplier locked(UUID id) { return suppliers.findByIdForUpdate(id).orElseThrow(() -> missing(id)); }
    private ApiException missing(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Supplier " + id + " was not found");
    }
}
