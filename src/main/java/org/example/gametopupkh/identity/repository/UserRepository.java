package org.example.gametopupkh.identity.repository;

import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.identity.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findWithRolesById(UUID id);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhoneNumber(String phoneNumber);
}
