package org.example.gametopupkh.auth.verification;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findByUserIdAndChannel(UUID userId, VerificationChannel channel);
}
