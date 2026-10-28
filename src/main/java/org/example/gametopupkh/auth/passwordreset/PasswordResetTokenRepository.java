package org.example.gametopupkh.auth.passwordreset;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<PasswordResetToken> findTopByUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(UUID userId);

    @Query("select t.user.id from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :usedAt "
            + "where t.user.id = :userId and t.usedAt is null")
    int invalidateActiveForUser(@Param("userId") UUID userId, @Param("usedAt") Instant usedAt);

    @Modifying(flushAutomatically = true)
    @Query("delete from PasswordResetToken t where t.expiresAt < :cutoff or t.usedAt < :cutoff")
    int deleteStale(@Param("cutoff") Instant cutoff);
}
