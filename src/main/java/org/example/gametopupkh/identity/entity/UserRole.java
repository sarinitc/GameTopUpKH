package org.example.gametopupkh.identity.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Link between a {@link User} and a {@link Role}. The {@code user_roles} table uses the
 * pair (user_id, role_id) as its primary key, so the identifier is embedded in the entity.
 */
@Entity
@Table(name = "user_roles")
@Getter
@Setter
@NoArgsConstructor
public class UserRole {

    @EmbeddedId
    private Id id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;

    public UserRole(User user, Role role) {
        this.id = new Id(user.getId(), role.getId());
        this.user = user;
        this.role = role;
    }

    /**
     * Composite primary key of the {@code user_roles} table.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "role_id", nullable = false)
        private Long roleId;

        public Id(UUID userId, Long roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }
    }
}
