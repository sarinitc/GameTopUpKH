package org.example.gametopupkh.catalog.game.controller;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.catalog.game.dto.request.GameRequest;
import org.example.gametopupkh.catalog.game.dto.response.GameResponse;
import org.example.gametopupkh.catalog.game.service.GameService;
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
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/api/v1/games")
    public ResponseEntity<ApiResponse<List<GameResponse>>> listActive() {
        return ResponseEntity.ok(ApiResponse.ok(gameService.findActive()));
    }

    @GetMapping("/api/v1/games/{id}")
    public ResponseEntity<ApiResponse<GameResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.findById(id)));
    }

    @PostMapping("/api/v1/admin/games")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<GameResponse>> create(
            @Validated(GameRequest.Create.class) @RequestBody GameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(gameService.create(request)));
    }

    @PutMapping("/api/v1/admin/games/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<GameResponse>> update(
            @PathVariable UUID id,
            @Validated(GameRequest.Update.class) @RequestBody GameRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.update(id, request)));
    }

    @PatchMapping("/api/v1/admin/games/{id}/status")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<GameResponse>> updateStatus(
            @PathVariable UUID id,
            @Validated(GameRequest.Status.class) @RequestBody GameRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.updateStatus(id, request)));
    }
}
