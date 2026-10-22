package org.example.gametopupkh.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only audit trail for a payment. Provider messages are never copied into
 * {@code note}; they may contain credentials or other secret material.
 */
@Entity
@Table(name = "payment_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_events_key", columnNames = {"payment_id", "event_key"})
})
@Getter
@Setter
@NoArgsConstructor
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "event_key", length = 255)
    private String eventKey;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
