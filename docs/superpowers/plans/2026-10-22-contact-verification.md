# Email and phone verification implementation plan

**Goal:** Add working code-request and confirmation endpoints for the email and phone already registered on an account.

**Architecture:** Keep verification in `auth.verification`. Store one hashed, expiring challenge per user/channel, serialize requests using a database user-row lock, and use a delivery interface. Development delivery logs codes only under the `dev` profile; other profiles fail closed until a real sender is configured.

**Tech stack:** Existing Java 17, Spring Boot, Spring MVC, Jakarta Validation, JPA, PostgreSQL/Flyway, BCrypt, and MockMvc/H2 tests. No new dependencies.

## Contract and constraints

- `POST /api/v1/auth/verify-email/request`: `{ "email": "..." }`.
- `POST /api/v1/auth/verify-email`: `{ "email": "...", "code": "123456" }`.
- `POST /api/v1/auth/verify-phone/request`: `{ "phoneNumber": "+855..." }`.
- `POST /api/v1/auth/verify-phone`: `{ "phoneNumber": "+855...", "code": "123456" }`.
- Request endpoints return the same 202 response for unknown contacts, already verified contacts, blocked accounts, and cooldown requests. Codes are never returned in HTTP responses.
- Six cryptographically random digits; BCrypt storage; 10-minute expiry; at most five failed attempts; 60-second resend cooldown. Resend replaces the previous challenge with a different code.
- Successful confirmation returns `{ "emailVerified": true/false, "phoneVerified": true/false }`, sets only the requested flag, and consumes the challenge. Account status and password checks do not change. Suspended/disabled accounts cannot verify.
- Wrong, expired, consumed, exhausted, unknown, or mismatched-contact codes return 400 `INVALID_VERIFICATION_CODE`. Failed-attempt updates must commit despite that error response.
- Sender failures return 503 and roll back a request. A profile without a configured sender returns 503 `VERIFICATION_DELIVERY_UNAVAILABLE`.
- Public endpoints accept code proof without requiring login. Existing login/registration/token behavior remains intact.
- Work in the current checkout to preserve the existing uncommitted auth/package migration. Do not commit or alter unrelated changes, accounts, or credentials.

## Tasks

1. **API regression test** (`src/test/java/org/example/gametopupkh/auth/VerificationApiTests.java`)
   - [ ] Observe request endpoint return 404 before implementation.
   - [ ] Test both channels, validation, contact normalization, status flags, replay, expiry, cross-channel use, resend, durable attempt limits, generic unknown-contact responses, blocked accounts, and delivery rollback.
2. **Persistence and verification service** (`src/main/java/org/example/gametopupkh/auth/verification/`, `identity/repository/UserRepository.java`, `db/migration/V4__contact_verification_codes.sql`)
   - [ ] Add channel, challenge entity/repository, sender interface, response, and `ContactVerificationService`.
   - [ ] Lock the matching user, issue and hash codes, enforce limits, consume proofs, and update the appropriate user flag.
3. **API and delivery** (`auth/verification/`, `README.md`, `auth/AuthResponse.java`)
   - [ ] Add validated email/phone request and confirmation DTOs, documented public controller methods, and dev-only console delivery with a non-dev unavailable sender.
   - [ ] Include verification flags in the authenticated user profile; document request/confirm examples and the development delivery limitation.
4. **Verification and review**
   - [ ] Run the new API tests, then the full suite on isolated H2 with seeded roles.
   - [ ] Review concurrency, transaction rollback, destination binding, code disclosure, profile isolation, and migration/entity consistency.
   - [ ] Check the OpenAPI contract and final diff; report any remaining delivery limitation.

## Review focus

Failed attempts must persist across HTTP requests. Concurrent sends/confirmations must not allow duplicate proof use. New codes must invalidate older ones. Codes must bind to the current registered contact and channel. Console delivery must never activate outside the development profile.
