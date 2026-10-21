package org.example.gametopupkh.catalog.game.service;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.catalog.game.dto.request.GameRequest;
import org.example.gametopupkh.catalog.game.dto.response.GameResponse;

public interface GameService {

    List<GameResponse> findActive();

    GameResponse findById(UUID id);

    GameResponse create(GameRequest request);

    GameResponse update(UUID id, GameRequest request);

    GameResponse updateStatus(UUID id, GameRequest request);
}
