-- Refund requests for completed payments. A request does not return money by itself;
-- the provider-confirmed state is stored separately from the order status.

CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL UNIQUE REFERENCES payments(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    provider VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reason VARCHAR(500),
    provider_reference VARCHAR(255),
    requested_by UUID REFERENCES users(id) ON DELETE SET NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT refunds_provider_chk CHECK (provider IN ('MOCK','ABA')),
    CONSTRAINT refunds_status_chk CHECK (status IN (
        'PENDING','PROCESSING','SUCCEEDED','FAILED','CANCELLED'
    ))
);
CREATE INDEX idx_refunds_status_created ON refunds(status, created_at DESC);
CREATE INDEX idx_refunds_order_created ON refunds(order_id, created_at DESC);
