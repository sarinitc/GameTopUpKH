-- Single-use password reset credentials. Only the SHA-256 hash of the secret is
-- stored; the raw value is delivered once to the account email address.
-- token_version is incremented on a successful reset so already-issued stateless
-- access tokens can be rejected by the JWT authentication filter.

ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    requested_ip VARCHAR(45),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT password_reset_tokens_hash_unique UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_tokens_user_created
    ON password_reset_tokens(user_id, created_at DESC);
CREATE INDEX idx_password_reset_tokens_expires
    ON password_reset_tokens(expires_at);
