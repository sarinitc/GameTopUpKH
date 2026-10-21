package org.example.gametopupkh.catalog.packagecatalog.service.impl;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.dto.request.GamePackageRequest;
import org.example.gametopupkh.catalog.packagecatalog.dto.response.GamePackageResponse;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.catalog.packagecatalog.service.GamePackageService;
import org.example.gametopupkh.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GamePackageServiceImpl implements GamePackageService {

    static final String DEFAULT_CURRENCY = "USD";

    private final GamePackageRepository gamePackageRepository;
    private final GameRepository gameRepository;

    public GamePackageServiceImpl(GamePackageRepository gamePackageRepository, GameRepository gameRepository) {
        this.gamePackageRepository = gamePackageRepository;
        this.gameRepository = gameRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GamePackageResponse> findActiveByGameId(UUID gameId) {
        requireGame(gameId);
        return gamePackageRepository.findAllByGameIdAndActiveTrueOrderByPriceAsc(gameId).stream()
                .map(GamePackageResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GamePackageResponse findById(UUID id) {
        return GamePackageResponse.from(findEntity(id));
    }

    @Override
    @Transactional
    public GamePackageResponse create(GamePackageRequest request) {
        String sku = normalizeSku(request.sku());
        if (gamePackageRepository.existsBySku(sku)) {
            throw new ApiException(HttpStatus.CONFLICT, "PACKAGE_SKU_EXISTS",
                    "A package with sku '" + sku + "' already exists");
        }
        requireGame(request.gameId());
        GamePackage gamePackage = new GamePackage();
        gamePackage.setGameId(request.gameId());
        gamePackage.setName(request.name().trim());
        gamePackage.setSku(sku);
        gamePackage.setDiamondAmount(request.diamondAmount());
        gamePackage.setPrice(request.price());
        gamePackage.setCurrency(currencyOrDefault(request.currency()));
        gamePackage.setActive(true);
        return GamePackageResponse.from(gamePackageRepository.save(gamePackage));
    }

    @Override
    @Transactional
    public GamePackageResponse update(UUID id, GamePackageRequest request) {
        GamePackage gamePackage = findEntity(id);
        gamePackage.setName(request.name().trim());
        gamePackage.setDiamondAmount(request.diamondAmount());
        gamePackage.setPrice(request.price());
        gamePackage.setCurrency(currencyOrDefault(request.currency()));
        return GamePackageResponse.from(gamePackageRepository.save(gamePackage));
    }

    @Override
    @Transactional
    public GamePackageResponse updateStatus(UUID id, GamePackageRequest request) {
        GamePackage gamePackage = findEntity(id);
        gamePackage.setActive(Boolean.TRUE.equals(request.active()));
        return GamePackageResponse.from(gamePackageRepository.save(gamePackage));
    }

    private GamePackage findEntity(UUID id) {
        return gamePackageRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                        "Package " + id + " was not found"));
    }

    private void requireGame(UUID gameId) {
        if (!gameRepository.existsById(gameId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND",
                    "Game " + gameId + " was not found");
        }
    }

    private String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    private String currencyOrDefault(String currency) {
        if (currency == null || currency.isBlank()) {
            return DEFAULT_CURRENCY;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
