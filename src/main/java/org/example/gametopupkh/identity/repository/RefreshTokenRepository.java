package org.example.gametopupkh.identity.repository;

import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
