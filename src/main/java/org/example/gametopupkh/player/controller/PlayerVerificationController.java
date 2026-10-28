package org.example.gametopupkh.player.controller;

import java.security.Principal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.example.gametopupkh.player.dto.PlayerVerifyResponse;
import org.example.gametopupkh.player.rate.PlayerVerificationRateLimiter;
import org.example.gametopupkh.player.service.PlayerVerificationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/games/{gameSlug}/player")
@Tag(name = "Player verification", description = "Verify in-game player ID before top-up")
@SecurityRequirement(name = "bearerAuth")
public class PlayerVerificationController {

    private final PlayerVerificationService playerVerificationService;
    private final PlayerVerificationRateLimiter rateLimiter;

    public PlayerVerificationController(PlayerVerificationService playerVerificationService,
                                        PlayerVerificationRateLimiter rateLimiter) {
        this.playerVerificationService = playerVerificationService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<PlayerVerifyResponse>> verify(
            @PathVariable String gameSlug,
            @RequestBody(required = false) PlayerVerifyRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "anonymous";
        long retryAfter = rateLimiter.tryAcquire(username);
        if (retryAfter > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(ApiResponse.error("PLAYER_VERIFICATION_RATE_LIMITED",
                            "Too many verification requests. Please try again later."));
        }

        PlayerVerifyResponse response = playerVerificationService.verify(gameSlug, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(response));
    }
}
