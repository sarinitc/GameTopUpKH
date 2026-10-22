package org.example.gametopupkh.auth;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.identity.entity.User;

/**
 * Tokens and profile returned after a successful register, login or refresh.
 *
 * @param tokenType    always {@code Bearer}
 * @param accessToken  short lived token used on every request
 * @param expiresIn    lifetime of the access token in seconds
 * @param refreshToken opaque token used to obtain a new access token
 * @param user         the authenticated profile
 */
public record AuthResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        String refreshToken,
        UserInfo user) {

    public static final String BEARER = "Bearer";

    public static AuthResponse of(String accessToken, long expiresIn, String refreshToken, User user) {
        return new AuthResponse(BEARER, accessToken, expiresIn, refreshToken, UserInfo.from(user));
    }

    /**
     * Profile snapshot of the authenticated user.
     */
    public record UserInfo(
            UUID id,
            String fullName,
            String email,
            String phoneNumber,
            boolean emailVerified,
            boolean phoneVerified,
            List<String> roles) {

        public static UserInfo from(User user) {
            return new UserInfo(
                    user.getId(),
                    user.getFullName(),
                    user.getEmail(),
                    user.getPhoneNumber(),
                    user.isEmailVerified(),
                    user.isPhoneVerified(),
                    List.copyOf(user.getRoleNames()));
        }
    }
}
