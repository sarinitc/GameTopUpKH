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

The base package is `org.example.gametopupkh`, split into `identity`, `catalog`, `supplier`,
`order`, `payment`, `fulfillment`, `refund`, `audit`, `messaging` and the shared
`common` / `config` / `security` packages. The application entry point is
`org.example.gametopupkh.GameTopUpKhApplication`.

The payment provider is chosen with `payment.gateway` (`mock` by default, `aba` for
AbaPayWay). Only the matching implementation is registered.

## API documentation

Swagger UI is served by springdoc-openapi:

- http://localhost:8082/swagger-ui.html
- OpenAPI JSON: http://localhost:8082/v3/api-docs

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

The identity module owns the `users`, `roles`, `user_roles` and `refresh_tokens` tables.
Registration returns a signed JWT access token and an opaque refresh token. The access
token travels in the `Authorization` header, the refresh token is only stored as a
SHA-256 hash so it can be revoked at any time.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | Create an account and get the first token pair |
| POST | `/api/v1/auth/login` | Sign in with email or phone number |
| POST | `/api/v1/auth/refresh` | Exchange a refresh token for a new token pair |
| POST | `/api/v1/auth/logout` | Revoke a refresh token |

Calling a protected endpoint:

```
curl -H "Authorization: Bearer <accessToken>" http://localhost:8082/api/v1/admin/games
```

Error codes: `EMAIL_ALREADY_REGISTERED` (409), `PHONE_NUMBER_ALREADY_REGISTERED` (409),
`CONTACT_REQUIRED` (400), `INVALID_CREDENTIALS` (401), `INVALID_REFRESH_TOKEN` (401),
`REVOKED_REFRESH_TOKEN` (401), `EXPIRED_REFRESH_TOKEN` (401), `ACCOUNT_NOT_ACTIVE` (403),
`UNAUTHORIZED` (401), `FORBIDDEN` (403).

### Module files

| File | Responsibility |
| --- | --- |
| `identity/entity/User.java` | Maps the users table to a Java entity |
| `identity/entity/Role.java` | Maps the roles table to a Java entity |
| `identity/entity/UserRole.java` | Maps the user_roles join table to a Java entity |
| `identity/entity/RefreshToken.java` | Maps the refresh_tokens table to a Java entity |
| `identity/repository/UserRepository.java` | Reads and writes users with JPA |
| `identity/repository/RoleRepository.java` | Reads and writes roles with JPA |
| `identity/repository/RefreshTokenRepository.java` | Reads and writes refresh tokens with JPA |
| `identity/service/AuthService.java` | Defines registration, login and token operations |
| `identity/service/AuthServiceImpl.java` | Implements the operations |
| `identity/controller/AuthController.java` | Exposes the `/api/v1/auth/**` endpoints |
| `identity/dto/RegisterRequest.java` | Validates the registration payload |
| `identity/dto/LoginRequest.java` | Validates the login payload |
| `identity/dto/AuthResponse.java` | Defines the tokens and profile returned to clients |
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
