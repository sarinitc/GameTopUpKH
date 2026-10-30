-- Ensure provider gateway reference is globally unique when assigned to prevent replaying transactions across different orders.

DROP INDEX IF EXISTS idx_payments_gateway_reference;
CREATE UNIQUE INDEX uq_payments_gateway_reference ON payments(gateway_reference) WHERE gateway_reference IS NOT NULL;
