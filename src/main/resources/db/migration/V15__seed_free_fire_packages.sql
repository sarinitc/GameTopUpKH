-- Seed a minimal, active Free Fire package catalog.
--
-- V14 guarantees the free-fire game row but intentionally seeds no packages, so
-- a fresh database returns an empty package list and the storefront cannot start
-- an order. This migration is idempotent: every row is keyed by its unique SKU,
-- so applying it to a database that already contains these SKUs changes nothing.
INSERT INTO game_packages (game_id, name, sku, diamond_amount, price, currency, is_active)
SELECT g.id, seed.name, seed.sku, seed.diamond_amount, seed.price, 'USD', TRUE
FROM games g
CROSS JOIN (
    VALUES
        ('100 Diamonds',  'FF-100',  100,  0.99),
        ('310 Diamonds',  'FF-310',  310,  2.99),
        ('520 Diamonds',  'FF-520',  520,  4.99),
        ('1060 Diamonds', 'FF-1060', 1060, 9.99)
) AS seed(name, sku, diamond_amount, price)
WHERE g.slug = 'free-fire'
  AND NOT EXISTS (
      SELECT 1 FROM game_packages existing WHERE existing.sku = seed.sku
  );
