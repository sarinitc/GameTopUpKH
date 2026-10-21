package org.example.gametopupkh.catalog.game.service.impl;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.example.gametopupkh.catalog.game.dto.request.GameRequest;
import org.example.gametopupkh.catalog.game.dto.response.GameResponse;
import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.game.service.GameService;
import org.example.gametopupkh.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameServiceImpl implements GameService {

    static final String DEFAULT_PLAYER_ID_LABEL = "Player ID";

    private final GameRepository gameRepository;

    public GameServiceImpl(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GameResponse> findActive() {
        return gameRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(GameResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GameResponse findById(UUID id) {
        return GameResponse.from(findEntity(id));
    }

    @Override
    @Transactional
    public GameResponse create(GameRequest request) {
        String slug = normalizeSlug(request.slug());
        if (gameRepository.existsBySlug(slug)) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_SLUG_EXISTS",
                    "A game with slug '" + slug + "' already exists");
        }
        Game game = new Game();
        game.setName(request.name().trim());
        game.setSlug(slug);
        game.setPlayerIdLabel(playerIdLabelOrDefault(request.playerIdLabel()));
        game.setRequiresZoneId(Boolean.TRUE.equals(request.requiresZoneId()));
        game.setActive(true);
        return GameResponse.from(gameRepository.save(game));
    }

    @Override
    @Transactional
    public GameResponse update(UUID id, GameRequest request) {
        Game game = findEntity(id);
        game.setName(request.name().trim());
        game.setPlayerIdLabel(playerIdLabelOrDefault(request.playerIdLabel()));
        game.setRequiresZoneId(Boolean.TRUE.equals(request.requiresZoneId()));
        return GameResponse.from(gameRepository.save(game));
    }

    @Override
    @Transactional
    public GameResponse updateStatus(UUID id, GameRequest request) {
        Game game = findEntity(id);
        game.setActive(Boolean.TRUE.equals(request.active()));
        return GameResponse.from(gameRepository.save(game));
    }

    private Game findEntity(UUID id) {
        return gameRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND",
                        "Game " + id + " was not found"));
    }

    private String normalizeSlug(String slug) {
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private String playerIdLabelOrDefault(String playerIdLabel) {
        if (playerIdLabel == null || playerIdLabel.isBlank()) {
            return DEFAULT_PLAYER_ID_LABEL;
        }
        return playerIdLabel.trim();
    }
}
