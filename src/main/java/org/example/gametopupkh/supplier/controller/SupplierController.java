package org.example.gametopupkh.supplier.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.supplier.dto.request.SupplierRequest;
import org.example.gametopupkh.supplier.dto.response.SupplierPageResponse;
import org.example.gametopupkh.supplier.dto.response.SupplierResponse;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.example.gametopupkh.supplier.service.SupplierService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/suppliers")
@SecurityRequirement(name = "bearerAuth")
public class SupplierController {
    private final SupplierService service;
    public SupplierController(SupplierService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<SupplierResponse>> create(
            @Validated(SupplierRequest.Create.class) @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(request)));
    }
    @GetMapping
    public ResponseEntity<ApiResponse<SupplierPageResponse>> findAll(
            @RequestParam(required = false) SupplierStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.findAll(status, page, size)));
    }
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.findById(id)));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierResponse>> update(@PathVariable UUID id,
            @Validated(SupplierRequest.Update.class) @RequestBody SupplierRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, request)));
    }
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<SupplierResponse>> updateStatus(@PathVariable UUID id,
            @Validated(SupplierRequest.Status.class) @RequestBody SupplierRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateStatus(id, request)));
    }
}
