package org.example.gametopupkh.supplier.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, UUID> {
    boolean existsBySupplierIdAndExternalSku(UUID supplierId, String externalSku);
    boolean existsBySupplierIdAndExternalSkuAndIdNot(UUID supplierId, String externalSku, UUID id);

    @Query("select p from SupplierProduct p where (:supplierId is null or p.supplierId = :supplierId) "
            + "and (:packageId is null or p.packageId = :packageId) and (:active is null or p.active = :active) "
            + "order by p.createdAt desc")
    List<SupplierProduct> findFiltered(@Param("supplierId") UUID supplierId, @Param("packageId") UUID packageId,
            @Param("active") Boolean active);

    @Query("select p from SupplierProduct p join Supplier s on s.id = p.supplierId "
            + "join GamePackage g on g.id = p.packageId "
            + "where p.packageId = :packageId and p.currency = :currency and p.active = true "
            + "and s.status = :status and g.active = true "
            + "order by p.costPrice asc, p.createdAt asc, p.id asc")
    List<SupplierProduct> findEligibleForSelection(@Param("packageId") UUID packageId,
            @Param("currency") String currency, @Param("status") SupplierStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SupplierProduct p where p.id = :id")
    Optional<SupplierProduct> findByIdForUpdate(@Param("id") UUID id);
}
