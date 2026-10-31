# GameTopUp API

Backend REST API for the GameTopUp learning project.

> **Portfolio / staging status.** Payments and game-credit delivery are
> **mocked** (`PAYMENT_GATEWAY=mock`, `SUPPLIER_CLIENT=mock`). Orders are
> recorded, but no money is captured and no diamonds are delivered. Email/SMS
> verification returns `503` outside development, and the public repository
> history contains an old `.env` whose secrets must be rotated before any real
> use. Deployment guides: [Render](docs/deployment/render.md) and
> [Docker Compose](docs/deployment/docker-production.md).

## Technologies
- Java
- Spring Boot
- PostgreSQL
- Docker
- Maven

## Deployment

| Target | Guide | Notes |
| --- | --- | --- |
| Render (Docker) + Neon | `docs/deployment/render.md` | Free tier sleeps when idle; CORS via `APP_CORS_ALLOWED_ORIGINS` |
| Self-hosted Docker Compose | `docs/deployment/docker-production.md` | Loopback-only port, TLS proxy in front, backups and rollback |

The production profile is `prod`, selected with `SPRING_PROFILES_ACTIVE=prod`.
It disables dev routes and Swagger, validates the schema with Flyway/Hibernate,
and refuses to start without database credentials and `JWT_SECRET`.

## Setup

1. Set POSTGRES_PASSWORD and JWT_SECRET in the project-root .env file using unquoted KEY=value entries.
2. Start PostgreSQL:
   docker compose up -d
3. Run Spring Boot from the project root. The default dev profile loads .env automatically.
4. Open http://localhost:8082. To override the local password, set POSTGRES_PASSWORD in the backend environment.

## Monitoring

Spring Boot Actuator uses the application's existing port (`8082` by default).
Only `health`, `info`, and `metrics` are exposed, with read-only access; all other
Actuator paths and write requests are denied.

| GET endpoint | Access and behavior |
| --- | --- |
| `/actuator/health` | Public status summary; an `ADMIN` JWT also reveals components and details |
| `/actuator/health/liveness` | Public status summary; independent of database availability |
| `/actuator/health/readiness` | Public status summary; checks `readinessState` and database connectivity |
| `/actuator/info` | `ADMIN` JWT required |
| `/actuator/metrics`, `/actuator/metrics/{name}` | `ADMIN` JWT required |

Health responses use Actuator's JSON format, for example `{"status":"UP"}`.
Health group details are also restricted to `ADMIN`. Unhealthy checks return HTTP
503. Probes should omit Authorization: an invalid bearer token returns HTTP 401
even on public health endpoints. Info and metrics return 401 without authentication
and 403 for authenticated users without `ADMIN`.

```powershell
curl.exe --fail http://localhost:8082/actuator/health
curl.exe --fail http://localhost:8082/actuator/health/liveness
curl.exe --fail http://localhost:8082/actuator/health/readiness
```

Use a valid admin access token in place of the placeholder in these HTTP requests:

```http
GET /actuator/info HTTP/1.1
Host: localhost:8082
Authorization: Bearer <ADMIN_ACCESS_TOKEN>

###
GET /actuator/metrics/jvm.memory.used HTTP/1.1
Host: localhost:8082
Authorization: Bearer <ADMIN_ACCESS_TOKEN>

###
GET /actuator/metrics/http.server.requests HTTP/1.1
Host: localhost:8082
Authorization: Bearer <ADMIN_ACCESS_TOKEN>
```

Info contains `app.name` (`GameTopUp-KH`), `app.description` (`Cambodian game-credit
storefront backend`), and `app.version`, resolved from Maven's `@project.version@`
during resource processing. Built-in metrics cover JVM memory and threads, CPU,
process uptime, JDBC/Hikari connection pools, and HTTP request counts and latency.
Metrics carry an `application` tag. HTTP request metrics appear after traffic;
generate a request before inspecting `http.server.requests`.

Metrics are held in memory and reset on restart. No persistence, dashboard, alerts,
or external exporter are configured. Health checks do not verify ABA PayWay,
suppliers, fulfillment progress, or business outcomes.

After a production restart, confirm anonymous health/liveness/readiness requests
return 200 with status only, admin info/metrics requests succeed, non-admin access
to info/metrics is denied, and other Actuator paths remain inaccessible. Generate
normal API traffic and confirm HTTP request measurements appear. Use the deployment's
configured host, port, and HTTPS endpoint for these checks. Roll back by reverting
the Actuator starter, monitoring configuration, and Actuator security matchers
together, then rebuilding and restarting the previous application. No database
migration or rollback is needed for monitoring.

Configuration reference: [Spring Boot Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
and [built-in metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html).

## Database schema

The schema is owned by Flyway. `src/main/resources/db/migration/V1__init.sql` holds the
initial schema and is applied automatically on startup, so no manual SQL step is needed.

To start from a clean database:

```
docker compose down -v
docker compose up -d
```

Schema changes after V1 go into new `V2__*.sql`, `V3__*.sql` migrations.

## Package layout

The base package is `org.example.gametopupkh`, split into `auth`, `identity`, `order`,
`catalog`, `supplier`, `payment`, `fulfillment`, `refund`, `audit`, `messaging` and the
shared `common` / `config` / `security` packages. The application entry point is
`org.example.gametopupkh.GameTopUpKhApplication`.

The payment provider is chosen with `payment.gateway` (`mock` by default, `aba` for
AbaPayWay). Only the matching implementation is registered.

### Payment structure

```text
payment/
├── Payment.java
├── PaymentEvent.java
├── PaymentStatus.java
├── PaymentProvider.java
├── PaymentRepository.java
├── PaymentEventRepository.java
├── PaymentService.java
├── PaymentServiceImpl.java
├── PaymentController.java
├── dto/
│   ├── CreatePaymentResponse.java
│   └── PaymentStatusResponse.java
├── gateway/
│   ├── PaymentGateway.java
│   └── MockPaymentGateway.java
├── reconciliation/
│   ├── PaymentReconciliationJob.java
│   ├── PaymentReconciliationResult.java
│   ├── PaymentReconciliationService.java
│   └── PaymentReconciliationServiceImpl.java
└── aba/
    ├── AbaPayWayGateway.java
    ├── AbaPayWayClient.java
    ├── AbaPayWayProperties.java
    ├── AbaPayWayHashService.java
    ├── AbaPayWayCallbackController.java
    ├── AbaPayWayCallbackVerifier.java
    ├── AbaPayWayPaymentMapper.java
    └── dto/
        ├── AbaQrRequest.java
        ├── AbaQrResponse.java
        ├── AbaCheckTransactionRequest.java
        ├── AbaCheckTransactionResponse.java
        └── AbaCallbackRequest.java
```

The mock and ABA PayWay adapters are implemented. ABA uses the official generate-QR
and check-transaction-2 JSON APIs with HMAC-SHA512 request hashes by default;
explicitly configured non-official paths retain the legacy configurable transport.
ABA configuration, HTTP transport, and response mapping live in separate classes.
The gateway's provider-neutral `PaymentRequest`, `PaymentIntent`, and
`PaymentGatewayException` are nested types of `PaymentGateway`; `PaymentStatus`
lives in the payment root.

Payments are persisted before the provider is contacted, and a local reference is
reserved with a unique constraint so retries reuse the same payment instead of
creating a second charge. The provider status is authoritative; checkout callbacks
only trigger a provider status lookup. Amount/currency mismatches and payments whose
order is no longer payable are moved to `NEEDS_REVIEW` for staff review.

Payment endpoints:

| Method | Path | Access |
| --- | --- | --- |
| POST | `/api/v1/orders/{orderId}/payments/aba` | authenticated owner |
| GET | `/api/v1/payments/{paymentId}` | authenticated owner or admin |
| POST | `/api/v1/payments/aba/callback` | public provider callback |
| POST | `/api/v1/admin/payments/{paymentId}/reconcile` | `ADMIN` |
| POST | `/api/v1/dev/payments/{paymentId}/success` | public, mock gateway only |
| GET/POST | `/api/v1/payments/mock/{reference}/confirm` | public, mock gateway only |

While `payment.gateway=mock`, use either mock confirmation endpoint from Swagger to
complete the payment and deliver its order through the configured supplier. With the
default mock supplier, the order reaches `COMPLETED`. Both endpoints accept a payment id,
local reference or gateway reference. The mock controller is not registered when the
`aba` gateway is active.

Reconciliation writes append-only `PaymentEvent` audit rows and is throttled per
payment with `payment.reconcile.cooldown` (default `PT30S`); a throttled non-final
reconciliation returns `429`. Set `payment.aba.callback-secret` to require
HMAC-SHA512 verification of callback payloads. The ABA adapter signs requests and
validates provider status codes; the `mock` gateway remains the default for local
development.

### Scheduled reconciliation

While the ABA gateway is active, `PaymentReconciliationJob` periodically inspects
unresolved ABA payments (`INITIALIZING`, `PENDING` and `NEEDS_REVIEW`). It selects a
bounded batch oldest first, skipping records newer than `payment.reconciliation.min-age`
and records checked within `payment.reconcile.cooldown`. Each provider lookup happens
before the write transaction; verified results go through the same amount/currency
checks, order transitions, audit events and after-commit fulfillment as a staff
reconciliation. One provider failure is recorded as `RECONCILIATION_ERROR` and does not
stop the rest of the batch. Other gateways and final payments are not inspected.

| Property | Description |
| --- | --- |
| `payment.reconciliation.enabled` | Enable scheduled scans (default `true`); set `PAYMENT_RECONCILIATION_ENABLED=false` to disable |
| `payment.reconciliation.batch-size` | Maximum payments per scan, a positive integer (default `50`) |
| `payment.reconciliation.fixed-delay` | Delay after each scan finishes (default `PT1M`) |
| `payment.reconciliation.initial-delay` | Delay before the first scan (default `PT30S`) |
| `payment.reconciliation.min-age` | Minimum age before a payment is eligible (default `PT1M`) |

## Supplier management

Supplier records and package mappings are managed by `ADMIN` users. The module
uses the existing `suppliers` and `supplier_products` tables; no additional
database migration is needed.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/v1/admin/suppliers` | Create a supplier |
| GET | `/api/v1/admin/suppliers` | List suppliers with pagination; optional `status` filter |
| GET | `/api/v1/admin/suppliers/{id}` | Get supplier details |
| PUT | `/api/v1/admin/suppliers/{id}` | Update the supplier name |
| PATCH | `/api/v1/admin/suppliers/{id}/status` | Set `ACTIVE`, `INACTIVE` or `SUSPENDED` |
| POST | `/api/v1/admin/supplier-products` | Map a package to a supplier |
| GET | `/api/v1/admin/supplier-products` | List mappings; optional `supplierId`, `packageId`, `active` filters |
| GET | `/api/v1/admin/supplier-products/{id}` | Get mapping details |
| PUT | `/api/v1/admin/supplier-products/{id}` | Update external SKU, cost and optional currency |
| PATCH | `/api/v1/admin/supplier-products/{id}/status` | Enable or disable a mapping |

The supplier list accepts zero-based `page` (default `0`, minimum `0`), `size`
(default `20`, range `1`–`100`), and optional `status` (`ACTIVE`, `INACTIVE` or
`SUSPENDED`). Results are always sorted by `createdAt DESC`, then `id DESC` to
break ties. All routes remain `ADMIN`-only.

Example request:

```http
GET /api/v1/admin/suppliers?page=0&size=20&status=ACTIVE
```

Example response when no active suppliers match:

```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "content": [],
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0
  },
  "timestamp": "2026-10-23T00:00:00Z"
}
```

The existing `ApiResponse` envelope is preserved, but `data` is now a pagination
object instead of an array. Clients must read supplier records from `data.content`;
each record retains the existing `SupplierResponse` fields. `page`, `size` and
`totalPages` are integers, and `totalElements` is a long integer counting matching
suppliers. A valid page beyond the last page returns `200` with empty `content`
and the actual totals.

Negative `page`, `size` outside `1`–`100`, or an offset (`page * size`) greater than
`2147483647` returns `400` with code `INVALID_PAGINATION`. Non-integer pagination
parameters return `400` with code `HTTP_400` in the existing error envelope.
Supplier-product listing and mutation endpoints are unchanged. This pagination
change adds no migration or audit-history implementation.

Create an active supplier (omitting `status` creates an `INACTIVE` supplier):

```json
{
  "name": "Demo Supplier",
  "status": "ACTIVE"
}
```

Use the returned supplier ID and an existing active catalog package ID to create
a mapping:

```json
{
  "supplierId": "<supplier UUID>",
  "packageId": "<package UUID>",
  "externalSku": "MLBB-86",
  "costPrice": 1.10,
  "currency": "USD"
}
```

Mappings default to active and currency defaults to `USD`. Names and SKUs are
trimmed and limited to 150 characters; external SKU case is preserved. Cost may
be zero, must be nonnegative, and allows at most ten integer and two fractional
digits. Currency must be three uppercase letters. A supplier's external SKU
must be unique; different suppliers may use the same SKU.

For PUT, send `externalSku`, `costPrice` and optionally `currency`; omitting
currency retains its current value. Supplier and package IDs cannot be reassigned.
Use PATCH with `{"active":false}` or `{"active":true}` for mapping status, and
`{"status":"SUSPENDED"}` for supplier status. Creating or enabling a mapping
requires an active supplier and active package. Disabling remains available
after a parent is deactivated. Supplier status changes preserve existing mapping
records and their active flags. Missing resources return 404, invalid requests
return 400, and duplicate SKUs or inactive-parent conflicts return 409.

These endpoints maintain supplier data. Delivery still uses the globally
configured adapter (`supplier.client=mock` by default); it does not yet route
orders using these mappings or send their external SKUs to a real provider.
`SupplierClientRegistry` resolves stable adapter keys, independently of editable
supplier names, and rejects unknown or duplicate keys.

## Supplier fulfillment

A verified payment starts supplier fulfillment after the payment transaction commits.
The order moves from `PAID` to `PROCESSING`, the supplier call is recorded as a
`FulfillmentAttempt`, and
the order becomes `COMPLETED` on success. There is at most one fulfillment per order,
and delivery/retry calls lock the order to serialize concurrent requests. Repeated
callbacks reuse the existing fulfillment. Mock delivery references are stable per
order (`MOCK-<orderNumber>`), including across application restarts.

Failed supplier calls are retried up to `supplier.max-attempts` (default `3`). When
all attempts fail, the fulfillment becomes `FAILED` and the order moves to `NEEDS_REVIEW`.
The verified payment remains `COMPLETED`. Staff-side application code can retry
without taking another payment by calling `FulfillmentService.retry(orderId)`. The
`mock` supplier always succeeds unless `supplier.mock.fail=true` is set.

If fulfillment could not start (for example, a transient database error), repeating
the payment callback or reconciling the completed payment starts it again. The
`FulfillmentWorker` also recovers eligible orders automatically: every 30 seconds,
it scans up to 50 orders, oldest first, for paid orders with missing or pending
fulfillment, plus processing orders with pending fulfillment. Each delivery uses
its own transaction; a failed startup does not stop the remaining batch. Exhausted
supplier failures require the explicit `retry` operation; callbacks and worker
scans do not reset their attempt budget. A rolled-back payment transaction never
starts delivery.

For local testing, keep `SUPPLIER_CLIENT=mock` alongside either `PAYMENT_GATEWAY=mock`
or the configured ABA sandbox gateway. Confirm the payment, then read the order
through the existing order API: the normal path is `PAID` -> `PROCESSING` ->
`COMPLETED`. Set `SUPPLIER_MOCK_FAIL=true` and restart to exercise the review path.
`FulfillmentService.findAttempts(orderId)` exposes the recorded attempt history;
`FulfillmentService.retry(orderId)` is a service operation, with no public retry endpoint.
`FulfillmentResponse.from(fulfillment, attempts)` creates an immutable response
snapshot containing delivery status and attempt history.

This adapter simulates delivery; it does not credit a real game account. Delivery
is synchronous with bounded retries. A durable queue and real supplier status
reconciliation are outside this mock module.

| Property | Description |
| --- | --- |
| `supplier.client` | Supplier adapter, currently `mock` (default) |
| `supplier.max-attempts` | Attempts per fulfillment or manual retry |
| `supplier.mock.fail` | Set `true` to exercise the failure and review path |
| `fulfillment.worker.enabled` | Enable recovery scans (default `true`); set `FULFILLMENT_WORKER_ENABLED=false` to disable |
| `fulfillment.worker.batch-size` | Maximum orders per scan, a positive integer (default `50`) |
| `fulfillment.worker.fixed-delay` | Delay after each scan finishes (default `PT30S`) |
| `fulfillment.worker.initial-delay` | Delay before the first scan (default `PT30S`) |

### Module files

| File | Responsibility |
| --- | --- |
| `fulfillment/Fulfillment.java` | Maps the fulfillments table to a Java entity |
| `fulfillment/FulfillmentStatus.java` | Lifecycle of a supplier fulfillment |
| `fulfillment/FulfillmentAttempt.java` | Append-only supplier attempt audit |
| `fulfillment/FulfillmentRepository.java` | Reads and writes fulfillments with JPA |
| `fulfillment/FulfillmentAttemptRepository.java` | Reads and writes attempts with JPA |
| `fulfillment/FulfillmentService.java` | Defines fulfillment and retry operations |
| `fulfillment/FulfillmentServiceImpl.java` | Calls the supplier and updates order status |
| `fulfillment/FulfillmentWorker.java` | Scheduled, bounded recovery of deliveries that have not started |
| `fulfillment/FulfillmentResponse.java` | Immutable delivery status and attempt history |
| `supplier/integration/SupplierClient.java` | Supplier adapter contract and stable adapter key |
| `supplier/integration/MockSupplierClient.java` | Development supplier that accepts deliveries |
| `supplier/integration/SupplierClientRegistry.java` | Resolves the configured adapter by key |
| `supplier/integration/SupplierTopupRequest.java` | Order, package, player, zone and quantity sent to the supplier |
| `supplier/integration/SupplierTopupResponse.java` | Delivery outcome, supplier reference and message |

## Audit trail

Successful administrative mutations are appended to the `audit_logs` table. Each
entry records the action, actor snapshot, resource, request description, IP address
and UTC timestamp. Audit rows are read-only through the API; there is no endpoint to
edit or delete them. Failed mutations and read-only requests are not recorded.

| Method | Endpoint | Access |
| --- | --- | --- |
| GET | `/api/v1/admin/audit-logs` | `ADMIN` |
| GET | `/api/v1/admin/audit-logs/{id}` | `ADMIN` |

The list endpoint supports zero-based `page` (default `0`), `size` (default `20`,
range `1`–`100`), and optional filters:

| Parameter | Meaning |
| --- | --- |
| `action` | `CREATE`, `UPDATE`, `DELETE`, `STATUS_CHANGE`, `RETRY`, `RECONCILE`, `REFUND`, `LOGIN` or `LOGOUT` |
| `actorId` or `actor` | Actor user id; `actor` also accepts an email address |
| `actorEmail` | Actor email snapshot, case-insensitive |
| `entityType` | Resource type such as `games`, `suppliers` or `payments`, case-insensitive |
| `entityId` | Resource identifier |
| `from` / `to` | Inclusive ISO-8601 instants, for example `2026-01-01T00:00:00Z` |

Results are ordered newest first, with the id as a tie-breaker. Negative pages,
sizes outside the allowed range and offsets above `2147483647` return
`INVALID_PAGINATION`; a `from` timestamp later than `to` returns
`INVALID_DATE_RANGE`. Unknown ids return `AUDIT_LOG_NOT_FOUND`. The trail is
created by migration `V10__audit_logs.sql`; take a normal database backup before
applying it to an existing environment.

## API documentation

Swagger UI is served by springdoc-openapi:

- http://localhost:8082/swagger-ui.html
- OpenAPI JSON: http://localhost:8082/v3/api-docs

To call protected endpoints in Swagger UI:

1. Register or log in with your real account details and copy `data.accessToken` from the response.
2. Click **Authorize** and paste the access token into `bearerAuth`, without the `Bearer ` prefix.
3. Click **Authorize**, close the dialog, and use **Try it out** on an order or admin endpoint.

Swagger adds `Authorization: Bearer <accessToken>` to protected requests. Admin endpoints
still require an account with the `ADMIN` role. Login, registration, token refresh, logout,
contact verification and public catalog requests do not require this authorization. When the access token
expires, obtain a new one through login or refresh and replace it in **Authorize**.

Set `springdoc.swagger-ui.enabled=false` to turn it off outside development.

## Game catalog

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/v1/games` | Get all active games |
| GET | `/api/v1/games/{id}` | Get game details |
| POST | `/api/v1/admin/games` | Create a game |
| PUT | `/api/v1/admin/games/{id}` | Update a game |
| PATCH | `/api/v1/admin/games/{id}/status` | Enable/disable a game |

All responses use the `ApiResponse` envelope (`success`, `code`, `message`, `data`, `timestamp`).
Error codes: `GAME_NOT_FOUND` (404), `GAME_SLUG_EXISTS` (409), `VALIDATION_FAILED` (400).

The `slug` is immutable after creation, and `PUT` replaces `name`, `playerIdLabel` and
`requiresZoneId`.

### Module files

| File | Responsibility |
| --- | --- |
| `game/entity/Game.java` | Maps the games table to a Java entity |
| `game/repository/GameRepository.java` | Reads and writes games with JPA |
| `game/service/GameService.java` | Defines business operations |
| `game/service/impl/GameServiceImpl.java` | Implements the operations |
| `game/controller/GameController.java` | Exposes REST endpoints |
| `game/dto/request/GameRequest.java` | Validates incoming data |
| `game/dto/response/GameResponse.java` | Defines data returned to clients |

`GameRequest` is shared by the three write endpoints. The controller applies a
validation group per operation (`GameRequest.Create`, `GameRequest.Update`,
`GameRequest.Status`) so each one only requires the fields it needs.

The `packagecatalog` module is scaffolded with the same layering
(`controller`, `service`, `repository`, `entity`, `dto`) and is ready for the
top-up package endpoints.

## Identity and authentication

The `auth` module owns the `refresh_tokens` table and the token lifecycle, the `identity`
module owns the `users`, `roles` and `user_roles` tables. Registration returns a signed
JWT access token and an opaque refresh token. The access token travels in the
`Authorization` header, the refresh token is only stored as a SHA-256 hash so it can be
revoked at any time.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | Create an account and get the first token pair |
| POST | `/api/v1/auth/login` | Sign in with email or phone number |
| POST | `/api/v1/auth/refresh` | Exchange a refresh token for a new token pair |
| POST | `/api/v1/auth/logout` | Revoke a refresh token |
| POST | `/api/v1/auth/verify-email/request` | Request a code for the registered email address |
| POST | `/api/v1/auth/verify-email` | Confirm the email verification code |
| POST | `/api/v1/auth/verify-phone/request` | Request a code for the registered phone number |
| POST | `/api/v1/auth/verify-phone` | Confirm the phone verification code |

Calling a protected endpoint:

```
curl -H "Authorization: Bearer <accessToken>" http://localhost:8082/api/v1/admin/games
```

Error codes: `EMAIL_ALREADY_REGISTERED` (409), `PHONE_NUMBER_ALREADY_REGISTERED` (409),
`CONTACT_REQUIRED` (400), `INVALID_CREDENTIALS` (401), `INVALID_REFRESH_TOKEN` (401),
`REVOKED_REFRESH_TOKEN` (401), `EXPIRED_REFRESH_TOKEN` (401), `ACCOUNT_NOT_ACTIVE` (403),
`UNAUTHORIZED` (401), `FORBIDDEN` (403).

### Email and phone verification

All four verification endpoints are public and accept JSON without a bearer token.
Use the email address or international phone number already registered on the account.

Request an email code with `POST /api/v1/auth/verify-email/request`:

```json
{ "email": "player@example.com" }
```

Confirm it with `POST /api/v1/auth/verify-email`:

```json
{ "email": "player@example.com", "code": "012345" }
```

Request a phone code with `POST /api/v1/auth/verify-phone/request`:

```json
{ "phoneNumber": "+85512345678" }
```

Confirm it with `POST /api/v1/auth/verify-phone`:

```json
{ "phoneNumber": "+85512345678", "code": "012345" }
```

When delivery is available, requests return HTTP 202 with the standard success envelope
and `data: null`. The response is the same for unknown contacts, already verified
contacts, suspended/disabled accounts and requests during the 60-second resend cooldown.
Codes are never returned in HTTP responses. Each six-digit code expires after 10 minutes
and allows at most five failed attempts. A resend replaces the previous code; a successful
confirmation consumes the code so it cannot be reused.

Confirmation returns HTTP 200 with both verification flags in `data`, for example after
verifying only an email address:

```json
{ "emailVerified": true, "phoneVerified": false }
```

Only the requested contact flag changes. The profile returned by registration, login
and token refresh also includes `emailVerified` and `phoneVerified`. Verification does
not change account status, password checks or the existing login/token behavior.
Invalid, expired, consumed, exhausted or mismatched codes return HTTP 400 with
`INVALID_VERIFICATION_CODE`; malformed payloads return `VALIDATION_FAILED` (400).
Delivery failures return HTTP 503 and do not save a new challenge.

**Development delivery only:** the `dev` profile logs each destination and code with a
`DEV ONLY` label. It does not send real email or SMS. This project defaults to `dev`,
so deployments must explicitly select a non-development profile, for example
`SPRING_PROFILES_ACTIVE=prod`, without also activating `dev`. Non-development profiles
return HTTP 503 `VERIFICATION_DELIVERY_UNAVAILABLE` for code requests until a real
`VerificationCodeSender` replaces the unavailable sender. They never log verification
codes. Configure an email/SMS provider before offering verification to real users.

### Module files

| File | Responsibility |
| --- | --- |
| `auth/AuthController.java` | Exposes the `/api/v1/auth/**` endpoints |
| `auth/AuthService.java` | Defines registration, login and token operations |
| `auth/AuthServiceImpl.java` | Implements the operations |
| `auth/RegisterRequest.java` | Validates the registration payload |
| `auth/LoginRequest.java` | Validates the login payload |
| `auth/RefreshTokenRequest.java` | Validates the refresh and logout payload |
| `auth/AuthResponse.java` | Defines the tokens and profile returned to clients |
| `auth/verification/ContactVerificationController.java` | Exposes public code-request and confirmation endpoints |
| `auth/verification/ContactVerificationService.java` | Issues and verifies expiring codes for registered contacts |
| `auth/verification/VerificationCodeSender.java` | Delivery interface with development and unavailable implementations |
| `auth/RefreshToken.java` | Maps the refresh_tokens table to a Java entity |
| `auth/RefreshTokenRepository.java` | Reads and writes refresh tokens with JPA |
| `identity/entity/User.java` | Maps the users table to a Java entity |
| `identity/entity/Role.java` | Maps the roles table to a Java entity |
| `identity/entity/UserRole.java` | Maps the user_roles join table to a Java entity |
| `identity/repository/UserRepository.java` | Reads and writes users with JPA |
| `identity/repository/RoleRepository.java` | Reads and writes roles with JPA |
| `security/JwtService.java` | Creates and validates access and refresh tokens |
| `security/JwtAuthenticationFilter.java` | Reads the bearer token on every request |
| `security/CustomUserDetailsService.java` | Loads a user and its roles for Spring Security |
| `security/SecurityConfig.java` | Defines the filter chain and the access rules |

### Access rules

| Path | Rule |
| --- | --- |
| `/api/v1/auth/**` | Public |
| `/api/v1/games/**`, `/api/v1/packages/**` | Public catalog |
| `/api/v1/admin/**` | Requires the `ADMIN` role |
| everything else | Requires a valid access token |

`SecurityConfig` answers with the same `ApiResponse` envelope: `UNAUTHORIZED` (401) when
the token is missing or broken, `FORBIDDEN` (403) when the role is missing.

### Configuration

| Property | Description |
| --- | --- |
| `app.jwt.secret` | Signing key of the access tokens, at least 32 characters |
| `app.jwt.issuer` | Value of the `iss` claim |
| `app.jwt.access-token-expiration` | Lifetime of an access token, for example `15m` |
| `app.jwt.refresh-token-expiration` | Lifetime of a refresh token, for example `7d` |

The `roles` table is seeded by `V2__seed_default_roles.sql`: every self registered
account gets the `USER` role, the `ADMIN` role unlocks the `/api/v1/admin/**` endpoints.

## Orders

The `order` module owns the `orders` table and the `order_status_history` table that
holds the status timeline of an order. The price and the currency always come from the
catalog, the client never sends them.

| Method | Endpoint | Purpose | Access |
| --- | --- | --- | --- |
| POST | `/api/v1/orders` | Create a new order | Customer |
| GET | `/api/v1/orders/my` | List my orders | Customer |
| GET | `/api/v1/orders/{id}` | Get order details | Owner/Admin |
| GET | `/api/v1/admin/orders` | List all orders | Admin |
| GET | `/api/v1/admin/orders/{id}` | Inspect one order | Admin |

`GET /api/v1/admin/orders` accepts an optional `status` query parameter.

### What the service validates before saving an order

1. The caller exists and the account is `ACTIVE`.
2. The game package exists and is active.
3. The parent game of the package exists and is active.
4. The player id is present, and a zone id is present when the game requires one.
5. The selling price and the currency are read from the catalog and copied into the order.
6. The order gets a unique order number, for example `ORD-20261022-4F1A9C02`.
7. The status starts at `PENDING_PAYMENT` and the first history row records the creation.

Error codes: `PACKAGE_NOT_FOUND` (404), `PACKAGE_INACTIVE` (400), `GAME_NOT_FOUND` (404),
`GAME_INACTIVE` (400), `PLAYER_ID_REQUIRED` (400), `ZONE_ID_REQUIRED` (400),
`ORDER_NOT_FOUND` (404), `ACCOUNT_NOT_ACTIVE` (403).

### Module files

| File | Responsibility |
| --- | --- |
| `order/Order.java` | Maps the orders table to a Java entity |
| `order/OrderStatus.java` | Lifecycle of an order |
| `order/OrderStatusHistory.java` | Maps the order_status_history table to a Java entity |
| `order/OrderRepository.java` | Reads and writes orders with JPA |
| `order/OrderStatusHistoryRepository.java` | Reads and writes status history with JPA |
| `order/OrderService.java` | Defines the order operations |
| `order/OrderServiceImpl.java` | Implements the operations and the catalog checks |
| `order/OrderController.java` | Exposes the `/api/v1/orders/**` and `/api/v1/admin/orders/**` endpoints |
| `order/OrderRequest.java` | Validates the order payload |
| `order/OrderResponse.java` | Defines the order and its status timeline returned to clients |
