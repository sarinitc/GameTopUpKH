package org.example.gametopupkh.catalog.packagecatalog.controller;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.dto.request.GamePackageRequest;
import org.example.gametopupkh.catalog.packagecatalog.dto.response.GamePackageResponse;
import org.example.gametopupkh.catalog.packagecatalog.service.GamePackageService;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GamePackageController {

    private final GamePackageService gamePackageService;

    public GamePackageController(GamePackageService gamePackageService) {
        this.gamePackageService = gamePackageService;
    }

    @GetMapping("/api/v1/games/{gameId}/packages")
    public ResponseEntity<ApiResponse<List<GamePackageResponse>>> listActiveByGame(@PathVariable UUID gameId) {
        return ResponseEntity.ok(ApiResponse.ok(gamePackageService.findActiveByGameId(gameId)));
    }

    @GetMapping("/api/v1/packages/{id}")
    public ResponseEntity<ApiResponse<GamePackageResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(gamePackageService.findById(id)));
    }

    @PostMapping("/api/v1/admin/packages")
    public ResponseEntity<ApiResponse<GamePackageResponse>> create(
            @Validated(GamePackageRequest.Create.class) @RequestBody GamePackageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(gamePackageService.create(request)));
    }

    @PutMapping("/api/v1/admin/packages/{id}")
    public ResponseEntity<ApiResponse<GamePackageResponse>> update(
            @PathVariable UUID id,
            @Validated(GamePackageRequest.Update.class) @RequestBody GamePackageRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(gamePackageService.update(id, request)));
    }

    @PatchMapping("/api/v1/admin/packages/{id}/status")
    public ResponseEntity<ApiResponse<GamePackageResponse>> updateStatus(
            @PathVariable UUID id,
            @Validated(GamePackageRequest.Status.class) @RequestBody GamePackageRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(gamePackageService.updateStatus(id, request)));
    }
}
