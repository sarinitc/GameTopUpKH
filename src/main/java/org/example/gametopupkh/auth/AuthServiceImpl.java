package org.example.gametopupkh.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.example.gametopupkh.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_ROLE = "USER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String fullName = request.fullName().trim();
        String email = normalizeEmail(request.email());
        String phoneNumber = normalizePhoneNumber(request.phoneNumber());
        if (email == null && phoneNumber == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONTACT_REQUIRED",
                    "Either email or phoneNumber is required");
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                    "A user with email '" + email + "' already exists");
        }
        if (phoneNumber != null && userRepository.existsByPhoneNumber(phoneNumber)) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_NUMBER_ALREADY_REGISTERED",
                    "A user with phone number '" + phoneNumber + "' already exists");
        }

        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);

        Role role = roleRepository.findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new IllegalStateException("The default role " + DEFAULT_ROLE + " is missing"));
        user.addRole(role);
        userRepository.save(user);

        return issueTokens(user);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String identifier = request.identifier().trim();
        Optional<User> found = identifier.contains("@")
                ? userRepository.findByEmailIgnoreCase(identifier)
                : userRepository.findByPhoneNumber(identifier);
        User user = found.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "The email, phone number or password is incorrect"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "The email, phone number or password is incorrect");
        }
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                    "The account is " + user.getAccountStatus().name().toLowerCase(Locale.ROOT));
        }
        return issueTokens(user);
    }

    @Override
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        Instant now = Instant.now();
        RefreshToken stored = refreshTokenRepository
                .findByTokenHashForUpdate(jwtService.hashRefreshToken(request.refreshToken()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "The refresh token is invalid"));
        if (stored.isRevoked()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REVOKED_REFRESH_TOKEN",
                    "The refresh token was already revoked");
        }
        if (stored.isExpired(now)) {
            stored.revoke(now);
            refreshTokenRepository.save(stored);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "EXPIRED_REFRESH_TOKEN",
                    "The refresh token has expired, please sign in again");
        }
        User user = stored.getUser();
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                    "The account is " + user.getAccountStatus().name().toLowerCase(Locale.ROOT));
        }
        stored.revoke(now);
        refreshTokenRepository.save(stored);
        return issueTokens(user);
    }

    @Override
    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByTokenHash(jwtService.hashRefreshToken(request.refreshToken()))
                .ifPresent(stored -> {
                    stored.revoke(Instant.now());
                    refreshTokenRepository.save(stored);
                });
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken();
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setTokenHash(jwtService.hashRefreshToken(refreshToken));
        stored.setExpiresAt(jwtService.getRefreshTokenExpiry());
        refreshTokenRepository.save(stored);
        return AuthResponse.of(accessToken, jwtService.getAccessTokenExpiresInSeconds(), refreshToken, user);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }
        return phoneNumber.trim();
    }
}
