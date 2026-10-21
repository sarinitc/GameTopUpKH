-- Default roles used by the identity module.
-- USER is granted to every self registered account, ADMIN unlocks the /api/v1/admin/** endpoints.

INSERT INTO roles (name) VALUES ('USER'), ('ADMIN');
