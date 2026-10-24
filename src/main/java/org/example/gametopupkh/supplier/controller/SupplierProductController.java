package org.example.gametopupkh.supplier.controller;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.supplier.dto.request.SupplierProductRequest;
import org.example.gametopupkh.supplier.dto.response.SupplierProductResponse;
import org.example.gametopupkh.supplier.service.SupplierProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/supplier-products")
@SecurityRequirement(name = "bearerAuth")
public class SupplierProductController {
    private final SupplierProductService service;
    public SupplierProductController(SupplierProductService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<SupplierProductResponse>> create(
            @Validated(SupplierProductRequest.Create.class) @RequestBody SupplierProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request)));
    }
    @GetMapping
    public ResponseEntity<ApiResponse<List<SupplierProductResponse>>> findAll(
            @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) UUID packageId,
            @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(ApiResponse.ok(service.findAll(supplierId, packageId, active)));
    }
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierProductResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.findById(id)));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierProductResponse>> update(@PathVariable UUID id,
            @Validated(SupplierProductRequest.Update.class) @RequestBody SupplierProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<SupplierProductResponse>> updateStatus(@PathVariable UUID id,
            @Validated(SupplierProductRequest.Status.class) @RequestBody SupplierProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateStatus(id, request)));
    }
}
