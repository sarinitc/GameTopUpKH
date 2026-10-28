package org.example.gametopupkh.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.gametopupkh.identity.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates and validates the JWT access tokens and the opaque refresh tokens.
 *
 * <p>The access token is a signed JWT that carries the user id, the email and the roles.
 * The refresh token is a random string: only its SHA-256 hash is stored, so the token
 * can be revoked by deleting or stamping the matching row.
 */
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TOKEN_VERSION = "ver";

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.access-token-expiration}") Duration accessTokenExpiration,
            @Value("${app.jwt.refresh-token-expiration}") Duration refreshTokenExpiration) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /**
     * @return a signed access token for the given user
     */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLES, List.copyOf(user.getRoleNames()))
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    /**
     * @return the id of the user the token was issued for
     * @throws JwtException when the token is malformed, tampered with or expired
     */
    public UUID extractUserId(String token) {
        Claims claims = parseClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * @return the token version the access token was issued with; tokens issued before
     *         versioning was introduced are treated as version {@code 0}
     * @throws JwtException when the token is malformed, tampered with or expired
     */
    public long extractTokenVersion(String token) {
        Object value = parseClaims(token).get(CLAIM_TOKEN_VERSION);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * @return a new random refresh token
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @return the hash of a refresh token, the only representation that gets stored
     */
    public String hashRefreshToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public Instant getAccessTokenExpiry() {
        return Instant.now().plus(accessTokenExpiration);
    }

    public Instant getRefreshTokenExpiry() {
        return Instant.now().plus(refreshTokenExpiration);
    }

    public long getAccessTokenExpiresInSeconds() {
        return accessTokenExpiration.toSeconds();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
