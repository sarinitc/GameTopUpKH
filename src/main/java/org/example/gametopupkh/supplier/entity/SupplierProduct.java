package org.example.gametopupkh.supplier.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "supplier_products", uniqueConstraints = @UniqueConstraint(
        name = "uq_supplier_products_external_sku", columnNames = {"supplier_id", "external_sku"}))
@Getter
@Setter
@NoArgsConstructor
public class SupplierProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;
    @Column(name = "external_sku", nullable = false, length = 150)
    private String externalSku;
    @Column(name = "cost_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPrice;
    @Column(nullable = false, length = 3)
    private String currency = "USD";
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
