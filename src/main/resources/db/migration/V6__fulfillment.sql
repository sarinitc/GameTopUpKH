-- Supplier fulfilment and its append-only delivery attempts.

CREATE TABLE fulfillments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    supplier_reference VARCHAR(255),
    last_error VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fulfillments_status_chk CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT fulfillments_attempt_count_chk CHECK (attempt_count >= 0)
);
CREATE INDEX idx_fulfillments_status_created ON fulfillments(status, created_at);

CREATE TABLE fulfillment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id UUID NOT NULL REFERENCES fulfillments(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(30) NOT NULL,
    supplier_reference VARCHAR(255),
    message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fulfillment_attempts_status_chk CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    CONSTRAINT uq_fulfillment_attempts_number UNIQUE (fulfillment_id, attempt_number)
);
CREATE INDEX idx_fulfillment_attempts_fulfillment ON fulfillment_attempts(fulfillment_id, attempt_number);
