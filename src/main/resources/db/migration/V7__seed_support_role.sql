-- SUPPORT can read fulfillment records for customer support; ADMIN handles retries.

INSERT INTO roles (name) VALUES ('SUPPORT') ON CONFLICT (name) DO NOTHING;
