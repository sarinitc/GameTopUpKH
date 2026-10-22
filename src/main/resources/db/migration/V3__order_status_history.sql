-- Order status history: every status transition of an order is appended here.
-- The first row of an order records the creation with PENDING_PAYMENT.

CREATE TABLE order_status_history (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                            status VARCHAR(40) NOT NULL,
                            note VARCHAR(255),
                            changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_status_history_status_chk CHECK (status IN ('PENDING_PAYMENT','PAID','PROCESSING','COMPLETED','FAILED','CANCELLED','REFUNDED','NEEDS_REVIEW'))
);
CREATE INDEX idx_order_status_history_order ON order_status_history(order_id, changed_at);
