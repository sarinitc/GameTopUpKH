# Player Verification Implementation Plan

**Goal:** Add reusable, authenticated player verification for Free Fire and Mobile Legends without changing payment semantics or fabricating account details.

**Architecture:** Use the existing game slug and API envelope. Add a `player` module with a provider interface, bounded lookup execution, per-user rate limiting, an opt-in development fixture adapter, and an unavailable supplier adapter pending an approved API contract. Reuse persisted order IDs and revalidate before any delivery request, outside the fulfillment claim transaction.

**Tech stack:** Existing Java 17, Spring Boot, Spring Security, JPA and JUnit/MockMvc; no new dependencies or schema migration.

## Decisions and constraints

- The request authorizes implementation. Work in the current checkout, preserving unrelated uncommitted work; no commits, reset, or migration edits.
- `AGENTS.md` was read; referenced `ai-agents` standards and roles are absent.
- No Next.js sources or Check ID button exist in this checkout or nearby repositories. Ask for their location; do not scaffold a replacement storefront.
- No approved supplier lookup API is present. Do not guess URLs/authentication or scrape. The supplier adapter reports unsupported and `UNAVAILABLE` until implemented against the approved contract.
- Canonical slugs: `free-fire`, `mobile-legends`. IDs remain strings, including leading zeroes. Provisional syntax bounds: 1–20 ASCII digits for player ID, 1–10 for ML zone. These bounds validate syntax, not account existence.
- The existing `/games/**` public matcher must be preceded by an authenticated POST matcher for player verification.
- Mock results require both an explicit property and `dev` without `prod`/`production`; only configured fixtures can produce account facts. Unknown fixtures return `UNAVAILABLE`.
- Existing order schema already stores submitted IDs. Numeric validation affects only the two supported slugs. Other games keep their current behavior.
- Rate limits are bounded in-process limits; multiple instances need an additional shared ingress limit.

## Tasks

- [ ] Add API tests first: authentication, malformed IDs, missing zones, inactive/missing games, unavailable provider and rate limiting.
- [ ] Add `player/dto/PlayerVerifyRequest`, `PlayerVerifyResponse`, `PlayerVerificationStatus`, and `player/PlayerAccountValidator`.
- [ ] Add `player/integration/PlayerLookupClient` with `supports(String)` and `lookup(String, PlayerVerifyRequest)` returning `PlayerVerifyResponse`; `provider()` identifies the configured adapter. Add mock and supplier adapters and explicit configuration.
- [ ] Add `player/service/PlayerVerificationService`: `verify(String, PlayerVerifyRequest)` and `Optional<PlayerVerifyResponse> revalidateForFulfillment(UUID packageId, String playerId, String zoneId)`. An empty result means the game/provider has no lookup support. Other results require `VERIFIED` before delivery.
- [ ] Implement bounded, cancelable lookup execution with no lookup retries. Null/malformed/mismatched responses and exceptions/timeouts become `UNAVAILABLE`; nickname/region are only returned for validated `VERIFIED` responses.
- [ ] Add controller, per-principal and global rate limits, `Retry-After`, `Cache-Control: no-store`, and security matcher.
- [ ] Add targeted order format validation and fulfillment preflight. Failed preflight makes no top-up attempt, marks fulfillment failed/order needs review, and permits only the existing explicit retry flow. Preserve uncertain top-up behavior.
- [ ] Add unit/API/profile/preflight tests; run focused and regression tests, then independently review.
- [ ] Document API/configuration, supplier adapter contract and pending Next.js integration (same-origin server route, server session credentials, nickname confirmation bound to current IDs, reset on edit/stale response).

## Review focus

Test leading zeroes; missing/mismatched zone IDs; provider errors that contain sensitive data; response identity mismatches; timeout cancellation and executor saturation; unknown mock accounts; combined dev/production profiles; rate limits across changing IDs; repeated fulfillment calls after preflight failure. Never treat lookup success as payment or delivery success.

## Execution evidence

- Initial wrapper invocation fails with its existing PowerShell null-array error. Cached Maven is available; dependency resolution was requested because the declared Spring Boot parent is not cached.
