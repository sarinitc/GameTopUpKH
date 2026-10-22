# GameTopUp API

Backend REST API for the GameTopUp learning project.

## Technologies
- Java
- Spring Boot
- PostgreSQL
- Docker
- Maven

## Setup

1. Set POSTGRES_PASSWORD and JWT_SECRET in the project-root .env file using unquoted KEY=value entries.
2. Start PostgreSQL:
   docker compose up -d
3. Run Spring Boot from the project root. The default dev profile loads .env automatically.
4. Open http://localhost:8082. To override the local password, set POSTGRES_PASSWORD in the backend environment.

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
complete the payment and move its order to `PAID`. Both endpoints accept a payment id,
local reference or gateway reference. The mock controller is not registered when the
`aba` gateway is active.

Reconciliation writes append-only `PaymentEvent` audit rows and is throttled per
payment with `payment.reconcile.cooldown` (default `PT30S`); a throttled non-final
reconciliation returns `429`. Set `payment.aba.callback-secret` to require
HMAC-SHA512 verification of callback payloads. The ABA adapter signs requests and
validates provider status codes; the `mock` gateway remains the default for local
development.

## Supplier fulfillment

A completed payment starts supplier fulfillment for its order. The order moves from
`PAID` to `PROCESSING`, the supplier call is recorded as a `FulfillmentAttempt`, and
the order becomes `COMPLETED` on success. There is at most one fulfillment per order,
so duplicate payment callbacks cannot deliver the same top-up twice.

Failed supplier calls are retried up to `supplier.max-attempts` (default `3`). When
all attempts fail, the fulfillment and order move to `NEEDS_REVIEW`; staff can retry
without taking another payment by calling `FulfillmentService.retry(orderId)`. The
`mock` supplier always succeeds unless `supplier.mock.fail=true` is set.

| Property | Description |
| --- | --- |
| `supplier.client` | Supplier adapter, currently `mock` (default) |
| `supplier.max-attempts` | Attempts per fulfillment or manual retry |
| `supplier.mock.fail` | Set `true` to exercise the failure and review path |

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
| `fulfillment/supplier/SupplierClient.java` | Supplier adapter contract and request/result types |
| `fulfillment/supplier/MockSupplierClient.java` | Development supplier that accepts deliveries |

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
