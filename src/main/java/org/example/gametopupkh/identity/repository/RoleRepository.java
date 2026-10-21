package org.example.gametopupkh.identity.repository;

import java.util.Optional;

import org.example.gametopupkh.identity.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);
}
