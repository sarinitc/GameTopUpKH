package org.example.gametopupkh.supplier;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.supplier.entity.Supplier;
import org.example.gametopupkh.supplier.entity.SupplierProduct;
import org.example.gametopupkh.supplier.enums.SupplierStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Committed catalog fixtures for fulfillment tests whose claims use separate transactions. */
public final class SupplierTestFixtures {
    private SupplierTestFixtures() {
    }

    public static SupplierProduct createEligibleProduct(EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> {
            Game game = new Game();
            game.setName("Fulfillment game");
            game.setSlug("fulfillment-" + UUID.randomUUID());
            entityManager.persist(game);

            GamePackage gamePackage = new GamePackage();
            gamePackage.setGameId(game.getId());
            gamePackage.setName("60 Diamonds");
            gamePackage.setSku("package-" + UUID.randomUUID());
            gamePackage.setDiamondAmount(60);
            gamePackage.setPrice(new BigDecimal("4.25"));
            entityManager.persist(gamePackage);

            Supplier supplier = new Supplier();
            supplier.setName("Test supplier " + UUID.randomUUID());
            supplier.setStatus(SupplierStatus.ACTIVE);
            entityManager.persist(supplier);

            SupplierProduct product = new SupplierProduct();
            product.setPackageId(gamePackage.getId());
            product.setSupplierId(supplier.getId());
            product.setExternalSku("supplier-sku-" + UUID.randomUUID());
            product.setCostPrice(new BigDecimal("2.00"));
            entityManager.persist(product);
            entityManager.flush();
            return product;
        });
    }
}
