-- One outstanding email or phone challenge per account. Never store the raw code.
CREATE TABLE contact_verification_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel VARCHAR(10) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT contact_verification_user_channel_unique UNIQUE (user_id, channel),
    CONSTRAINT contact_verification_channel_chk CHECK (channel IN ('EMAIL', 'PHONE')),
    CONSTRAINT contact_verification_attempts_chk CHECK (failed_attempts BETWEEN 0 AND 5)
);
