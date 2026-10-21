package org.example.gametopupkh.catalog.game.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.catalog.game.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, UUID> {

    List<Game> findAllByActiveTrueOrderByNameAsc();

    Optional<Game> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
