package org.example.gametopupkh.catalog.packagecatalog.service;

import java.util.List;
import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.dto.request.GamePackageRequest;
import org.example.gametopupkh.catalog.packagecatalog.dto.response.GamePackageResponse;

public interface GamePackageService {

    List<GamePackageResponse> findActiveByGameId(UUID gameId);

    GamePackageResponse findById(UUID id);

    GamePackageResponse create(GamePackageRequest request);

    GamePackageResponse update(UUID id, GamePackageRequest request);

    GamePackageResponse updateStatus(UUID id, GamePackageRequest request);
}
