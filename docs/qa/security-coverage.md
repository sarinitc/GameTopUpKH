# Security test coverage

`AuthSecurityPostgresTests` uses the shared PostgreSQL 17 Testcontainers fixture, Flyway schema, MockMvc filters, and real persisted users/JWTs. Requests and token responses are not printed. It covers:

- Registration normalization, duplicate case-insensitive email rejection, password hashing, refresh-token hashing, and default customer role.
- Login, committed refresh rotation, old-token rejection, idempotent logout, expired-refresh rejection, and no session creation for invalid credentials.
- Concurrent refresh reuse with two real requests synchronized at a PostgreSQL row-lock boundary; exactly one successor may be committed.
- Existing JWT invalidation after account suspension/disablement and immediate removal of administrator privileges after a role change.
- Customer order ownership, hidden cross-customer and support access, anonymous rejection, and administrator reads.
- Denied or invalid administrator mutations creating neither business rows nor successful audit entries.
- Successful administrative audit actor attribution and exclusion of raw request bodies, bearer tokens, API-key headers, and query secrets.

`MockRouteIsolationPostgresTests` runs with the `production` profile and deliberately retains only fake adapters through the test resources. It requires both development controllers to be absent, then attempts each payment-confirmation and fulfillment route anonymously and as an administrator. It checks the actual payment, order, fulfillment, attempt, and history rows remain unchanged.

The existing `AuthApiTests`, `OrderApiTests`, catalog API tests, and `AuditLogApiTests` retain broader endpoint coverage. Existing `ActuatorApiTests` check public health probe redaction, administrator-only operational data, sensitive endpoint denial, and database-outage readiness behavior. Those tests predate this file and are not evidence that the new PostgreSQL tests have run.

## Review findings and limits

- Mock payment and fulfillment mutation controllers are conditional on provider selection but were not scoped to an isolated development profile during the initial review. `SecurityConfig` permits anonymous access to these routes. A mock provider setting is insufficient protection when an application is reachable publicly; this requires explicit route/profile protection and regression coverage before deployment.
- Refresh rotation initially read and revoked a token without a pessimistic lock, conditional update, or version check. The new committed concurrent PostgreSQL regression test is expected to expose this until a minimal locking fix is applied.
- `GameRequest` and `GamePackageRequest` custom validation groups initially did not inherit `Default`; their size, format, positivity, and precision constraints could therefore be skipped by the controllers. Supplier request groups already inherit `Default`. A minimal group fix and boundary tests should address this separately.
- `UnavailableVerificationCodeSender` deliberately returns 503 outside `dev`; verification tests should supply a fake sender rather than enabling console delivery or a live email/SMS provider. Console delivery logs verification codes in development and is outside the new audit-persistence assertions.
- Audit persistence currently occurs after successful controller processing and logs persistence failures. These tests do not establish atomicity between business mutations and audit writes during database failures.
- These tests verify stored audit metadata, not every application log or arbitrary unhandled-exception response. `GlobalExceptionHandler` has no explicit generic exception or database-conflict mapping; concurrency and constraint errors require additional review.

No live payment, supplier, email, or SMS operation is part of this test class. Test execution results belong in the root validation report after the shared infrastructure has compiled and run.
