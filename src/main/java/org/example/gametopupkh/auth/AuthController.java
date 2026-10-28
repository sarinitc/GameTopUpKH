package org.example.gametopupkh.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.gametopupkh.auth.passwordreset.ForgotPasswordRequest;
import org.example.gametopupkh.auth.passwordreset.MessageResponse;
import org.example.gametopupkh.auth.passwordreset.PasswordResetService;
import org.example.gametopupkh.auth.passwordreset.PasswordResetServiceImpl;
import org.example.gametopupkh.auth.passwordreset.ResetPasswordRequest;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login, token lifecycle and password recovery")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Validated @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Validated @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Validated @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Validated @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "Request password reset instructions",
            description = "Always returns a generic response, whether or not the email is registered.")
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<MessageResponse>> forgotPassword(
            @Validated @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(
                new MessageResponse(PasswordResetServiceImpl.FORGOT_PASSWORD_MESSAGE)));
    }

    @Operation(summary = "Reset a password with a single-use token",
            description = "Consumes the emailed token, revokes refresh tokens and invalidates existing access tokens.")
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<MessageResponse>> resetPassword(
            @Validated @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        passwordResetService.resetPassword(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(
                new MessageResponse(PasswordResetServiceImpl.PASSWORD_RESET_MESSAGE)));
    }
}
