package org.example.gametopupkh.identity.service;

import org.example.gametopupkh.identity.dto.AuthResponse;
import org.example.gametopupkh.identity.dto.LoginRequest;
import org.example.gametopupkh.identity.dto.RefreshTokenRequest;
import org.example.gametopupkh.identity.dto.RegisterRequest;

/**
 * Registration, login and token lifecycle of the identity module.
 */
public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request);
}
