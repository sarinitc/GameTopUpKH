package org.example.gametopupkh.catalog.packagecatalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GamePackageRepository extends JpaRepository<GamePackage, UUID> {

    List<GamePackage> findAllByGameIdAndActiveTrueOrderByPriceAsc(UUID gameId);

    Optional<GamePackage> findBySku(String sku);

    boolean existsBySku(String sku);
}
