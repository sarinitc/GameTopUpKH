-- Add Bakong KHQR payment provider support, QR MD5 tracking, and transaction hash uniqueness.

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_provider_chk;
ALTER TABLE payments ADD CONSTRAINT payments_provider_chk CHECK (provider IN ('MOCK', 'ABA', 'BAKONG'));

ALTER TABLE payments ADD COLUMN IF NOT EXISTS qr_md5 VARCHAR(32);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS bakong_hash VARCHAR(255);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS verification_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_payments_qr_md5 ON payments(qr_md5) WHERE qr_md5 IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_bakong_hash ON payments(bakong_hash) WHERE bakong_hash IS NOT NULL;
