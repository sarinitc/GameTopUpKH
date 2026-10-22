-- Payment attempts and their append-only audit trail.
-- The local payment reference is unique and reserved before any provider call.

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
    provider VARCHAR(30) NOT NULL,
    payment_reference VARCHAR(20) NOT NULL UNIQUE,
    gateway_reference VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    qr_string VARCHAR(4096),
    qr_image_url VARCHAR(2048),
    redirect_url VARCHAR(2048),
    expires_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    last_provider_check_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payments_provider_chk CHECK (provider IN ('MOCK','ABA')),
    CONSTRAINT payments_status_chk CHECK (status IN (
        'INITIALIZING','PENDING','COMPLETED','FAILED','EXPIRED','CANCELLED','NEEDS_REVIEW'
    ))
);
CREATE INDEX idx_payments_gateway_reference ON payments(gateway_reference);
CREATE INDEX idx_payments_status_created ON payments(status, created_at DESC);

CREATE TABLE payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    event_key VARCHAR(255),
    note VARCHAR(255),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_events_key UNIQUE (payment_id, event_key)
);
CREATE INDEX idx_payment_events_payment_created ON payment_events(payment_id, created_at);
