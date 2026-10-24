-- Persist selected supplier mapping on fulfilment

ALTER TABLE fulfillments
    ADD COLUMN supplier_id UUID,
    ADD COLUMN supplier_product_id UUID,
    ADD COLUMN supplier_sku VARCHAR(150),
    ADD COLUMN supplier_adapter_key VARCHAR(100);

CREATE INDEX idx_fulfillments_supplier_product ON fulfillments(supplier_product_id);
