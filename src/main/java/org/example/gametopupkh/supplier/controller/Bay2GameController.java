package org.example.gametopupkh.supplier.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.supplier.integration.bay2game.Bay2GameClient;
import org.example.gametopupkh.supplier.integration.bay2game.Bay2GameResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative reads only; this controller never creates supplier orders or local mappings. */
@RestController
@RequestMapping("/api/v1/admin/suppliers/bay2game")
@SecurityRequirement(name = "bearerAuth")
public class Bay2GameController {
    private final ObjectProvider<Bay2GameClient> clients;

    public Bay2GameController(ObjectProvider<Bay2GameClient> clients) {
        this.clients = clients;
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<Bay2GameResponses.Profile>> profile() {
        return response(client().profile());
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Bay2GameResponses.Balance>> balance() {
        return response(client().balance());
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<Bay2GameResponses.Categories>> categories() {
        return response(client().categories());
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<Bay2GameResponses.Products>> products(
            @RequestParam(required = false) String gameCode) {
        return response(client().products(gameCode));
    }

    private Bay2GameClient client() {
        Bay2GameClient client = clients.getIfAvailable();
        if (client == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BAY2GAME_DISABLED",
                    "Bay2Game integration is disabled");
        }
        return client;
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
