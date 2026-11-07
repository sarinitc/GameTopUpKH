# Render Deployment (Docker)

Deploying the same `Dockerfile` used for the Compose host on Render's Docker
runtime, with the database on Neon. This is the quickest public deployment:
no server administration, TLS is handled by Render.

> **Mock commerce.** Payments (`PAYMENT_GATEWAY=mock`) and delivery
> (`SUPPLIER_CLIENT=mock`) are simulated. The service records orders and can be
> driven through the mock flows only with dev tools enabled; in this deployment
> dev tools are off, so orders stop at `PENDING_PAYMENT` and nothing charges or
> delivers. Do not send real customers to this endpoint expecting diamonds.

## What Render runs

| Piece | Setting |
| --- | --- |
| Runtime | Docker (`./Dockerfile`, multi-stage Maven → JRE 17) |
| Port | Render injects `PORT`; the app binds `${PORT:${SERVER_PORT:8082}}` |
| Health check | `/actuator/health/readiness` (200 with `status: UP` only when the DB answers) |
| Profiles | Image default `ENV SPRING_PROFILES_ACTIVE=prod`, overridable from the dashboard |
| Schema | Flyway runs `V1`–`V13` on the first boot, then Hibernate validates only |
| Database | External Neon PostgreSQL (`sslmode=require`), no Render disk |
| Start command | Image `ENTRYPOINT` (`java -jar /app/application.jar`); do not override |

`render.yaml` at the repository root is an optional Blueprint. A manual
**New → Web Service** with runtime **Docker** is equivalent.

## Deploy on Render + Neon (quick start)

1. **Neon:** create a project, then copy the **direct** host (the one *without*
   `-pooler`) — Flyway's migration lock does not survive transaction pooling.
   Build the JDBC URL as
   `jdbc:postgresql://<neon-host>/neondb?sslmode=require` and keep the user and
   password as separate values. If the password was ever pasted into a chat, a
   ticket or a public repo, reset it in the Neon console first.
2. **Render:** **New → Web Service** → connect the repo → runtime **Docker** →
   Dockerfile `./Dockerfile` → health check path `/actuator/health/readiness`.
   Leave the start command empty; the image already defaults to
   `SPRING_PROFILES_ACTIVE=prod` and binds Render's injected `PORT`.
3. **Env vars:** set the block below in the Render dashboard and deploy.
4. **Frontend:** point the Vercel API base URL at the Render host, then make
   `APP_CORS_ALLOWED_ORIGINS` match the Vercel origin exactly (https, no path,
   no trailing slash). Changing any env var restarts the service.
5. **Verify:** `curl.exe --fail https://<service>.onrender.com/actuator/health`
   must return `{"groups":["liveness","readiness"],"status":"UP"}`, and the
   readiness path (the Render health check) must report `UP` after Flyway
   finishes `V1`–`V13` on the first boot.

```properties
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://<neon-host>/neondb?sslmode=require
SPRING_DATASOURCE_USERNAME=<neon-user>
SPRING_DATASOURCE_PASSWORD=<NEON_PASSWORD>
JWT_SECRET=<64-plus-random-characters>
APP_CORS_ALLOWED_ORIGINS=https://gametopupkh.cheavsaren65.workers.dev
APP_PASSWORD_RESET_FRONTEND_BASE_URL=https://gametopupkh.cheavsaren65.workers.dev
DB_POOL_SIZE=5
DB_POOL_MIN_IDLE=2
```

Do **not** set `PORT`: Render injects it. Do **not** copy the API key or gateway
values from the local `.env`; Render runs mock commerce
(`PAYMENT_GATEWAY=mock`, `SUPPLIER_CLIENT=mock`).

> **Free tier sleeps.** The free instance suspends after ~15 minutes idle, so the
> next request can take about 50 seconds. The data lives in Neon and is not
> affected; mention the cold start to anyone you share the link with.

The sections below explain each step, the Blueprint file, CORS preflight checks
and troubleshooting.

## 1. Create the Neon database

1. Create a Neon project and database (any name, e.g. `gametopup`).
2. In **Connection Details**, copy the **direct** connection details (the host
   *without* `-pooler`). Flyway takes a migration lock, which pooled transaction
   mode can break.
3. Build the JDBC URL from those values:

```
jdbc:postgresql://<neon-host>/<database>?sslmode=require
```

Keep host, database, user and password separate — Render needs them as four
different variables.

## 2. Create the Render service

1. Render → **New → Web Service** → connect `github.com/sarinitc/GameTopUpKH`.
2. Language/runtime: **Docker**. Dockerfile path: `./Dockerfile`.
3. Region: Singapore (closest to Cambodia). Plan: Free is enough for a portfolio.
4. Health check path: `/actuator/health/readiness`.
5. Do **not** set a start command; the image entrypoint is correct.

## 3. Environment variables

Required (startup fails without them):

| Variable | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<neon-host>/<database>?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Neon user, e.g. `neondb_owner` |
| `SPRING_DATASOURCE_PASSWORD` | the **new** Neon password |
| `JWT_SECRET` | new random value, **32+ characters** (64+ recommended) |
| `APP_PASSWORD_RESET_FRONTEND_BASE_URL` | `https://your-app.vercel.app` |

Recommended:

| Variable | Value | Why |
| --- | --- | --- |
| `APP_CORS_ALLOWED_ORIGINS` | `https://your-app.vercel.app` | Without it the browser frontend is blocked |
| `DB_POOL_SIZE` | `5` | Neon connection limits are modest |
| `DB_POOL_MIN_IDLE` | `2` | Avoid holding idle connections |

Optional (defaults shown; keep them for a portfolio deployment):

```
APP_DEV_TOOLS_ENABLED=false
PAYMENT_GATEWAY=mock
SUPPLIER_CLIENT=mock
FULFILLMENT_WORKER_ENABLED=false
PAYMENT_RECONCILIATION_ENABLED=false
SPRINGDOC_ENABLED=false
```

**Do not set `PORT`.** Render injects it and the app reads it. If you do set it
(some guides say `PORT=8082`), the app still binds to that value and Render
routes to it — but the injected value is the reliable option.

A Blueprint deployment prompts for every `sync: false` variable instead.

Generate the JWT secret (PowerShell or OpenSSL). 48 random bytes become a 72
character Base64 string:

```powershell
[Convert]::ToBase64String([byte[]](1..48 | ForEach-Object { Get-Random -Maximum 256 }))
```

```bash
openssl rand -base64 64
```

Rotating `JWT_SECRET` later signs out every existing session. Never reuse the
value that was committed in `.env` and pushed to this public repository.

## 4. Deploy and verify

Render builds the image (first build takes a few minutes: Maven downloads the
dependencies), applies migrations, and starts the app. Watch the **Logs** tab for
`Started GameTopUpKhApplication` and the Flyway summary.

```powershell
curl.exe --fail https://<your-service>.onrender.com/actuator/health
curl.exe --fail https://<your-service>.onrender.com/actuator/health/readiness
curl.exe -s -o NUL -w "%{http_code}`n" https://<your-service>.onrender.com/api/v1/games
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`, readiness `UP`,
and `200` for the public games list. `/v3/api-docs` must return `401` for an
anonymous caller: Swagger is disabled by default in `prod`, and the docs paths
require an ADMIN token even when Springdoc is not registered.

### Production Swagger (opt-in, ADMIN only)

Swagger is off unless `SPRINGDOC_ENABLED=true` is set on the Render service.
Enabling it does **not** make the docs public: `application-prod.yml` keeps
`app.openapi.public: false`, so `SecurityConfig` requires a valid ADMIN JWT for
`/v3/api-docs`, `/v3/api-docs/**`, `/swagger-ui.html` and `/swagger-ui/**`.
Do not set `APP_OPENAPI_PUBLIC=true` unless you deliberately want every caller
to read the API document.

Verify the opt-in with an admin login:

```powershell
$login = Invoke-RestMethod -Method Post `
  -Uri "https://<your-service>.onrender.com/api/v1/auth/login" `
  -ContentType "application/json" `
  -Body '{"identifier":"<admin-email>","password":"<admin-password>"}'

Invoke-RestMethod -Uri "https://<your-service>.onrender.com/v3/api-docs" `
  -Headers @{ Authorization = "Bearer $($login.data.accessToken)" }
```

A `200` with an `openapi` field proves Springdoc is registered *and* the caller is
an ADMIN. Anonymous browsers cannot open Swagger UI because navigation cannot
attach a bearer token to the HTML request. To use the UI, inject the header for
the Render origin only (ModHeader, Requestly, or a dedicated browser profile):

| Header | Value | Scope |
| --- | --- | --- |
| `Authorization` | `Bearer <accessToken>` | `https://<your-service>.onrender.com/*` |

Then open `https://<your-service>.onrender.com/swagger-ui/index.html` and remove
or disable the header when finished. Access tokens live for 15 minutes; renew
them with `POST /api/v1/auth/refresh` and update the header value. Prefer curl
or Postman when a browser extension is not acceptable.

| Response | Meaning |
| --- | --- |
| `401` | No token, or the access token expired or is malformed |
| `403` | Authenticated, but the account does not have `ROLE_ADMIN` |
| `404` | Springdoc is not registered: `SPRINGDOC_ENABLED=true` is missing on the running service, or the deploy is behind the current commit |

### First ADMIN provisioning (one-time, server-side)

Registration never creates an administrator. The registration payload has no role
field and `AuthServiceImpl` always assigns `USER`; there is no public
admin-registration endpoint. Use the built-in one-time bootstrap instead of editing
roles by hand.

The bootstrap runs at startup only when it is explicitly enabled, and is disabled
by default. It targets exactly one existing account by email, never accepts a
client-supplied role, and writes an `ADMIN_BOOTSTRAP` audit row.

**Prerequisites**

1. The target account already exists (register normally first).
2. `users.account_status = 'ACTIVE'`.
3. `users.email_verified = true` **or** `users.phone_verified = true`.
4. `SPRINGDOC_ENABLED=true` on the running service if you also want the docs.

> **Verification blocker:** outside the `dev` profile the app uses
> `UnavailableVerificationCodeSender`, so `POST /api/v1/auth/verify/...` returns
> `503 VERIFICATION_DELIVERY_UNAVAILABLE`. A fresh production account is usually
> unverified, and the bootstrap will fail closed. Configure a real email/SMS sender
> (or verify the account through an approved path) before expecting a grant. Do
> **not** update `email_verified`/`phone_verified` directly in Neon without a
> reviewed, backed-up, explicitly approved operation.

**Safe production sequence**

1. Take a Neon branch or backup (Neon → Branches → create a branch, or export the
   database). Record the current time.
2. Run read-only prechecks against the direct (non-`-pooler`) Neon host:
   ```sql
   SELECT u.id, u.email, u.account_status, u.email_verified, u.phone_verified,
          COALESCE(string_agg(r.name, ',' ORDER BY r.name), '') AS roles
   FROM users u
   LEFT JOIN user_roles ur ON ur.user_id = u.id
   LEFT JOIN roles r ON r.id = ur.role_id
   WHERE lower(u.email) = lower('<target-email>')
   GROUP BY u.id;
   ```
   Confirm the row is `ACTIVE`, verified, and currently has only `USER` (or no
   `ADMIN`).
3. Add these environment variables to the Render service:
   ```
   APP_ADMIN_BOOTSTRAP_ENABLED=true
   APP_ADMIN_BOOTSTRAP_EMAIL=<target-email>
   APP_ADMIN_BOOTSTRAP_CONFIRM=GRANT_ADMIN
   ```
   Keep `SPRINGDOC_ENABLED=true` if you want Swagger afterwards.
4. Save and redeploy. Watch the **Logs** tab. Success prints:
   ```
   One-time ADMIN bootstrap completed: GRANTED
   ```
   `ALREADY_ADMIN` means the account already had the role and nothing changed.
   A `failed and no role was granted` line means a precondition failed; fix it and
   redeploy, do not bypass the check.
5. Immediately remove all three `APP_ADMIN_BOOTSTRAP_*` variables (and redeploy).
   Leaving them enabled is unnecessary: a later email change would grant a second
   account on the next restart. The runner is idempotent, but the variables are
   meant to live for one deploy only.
6. Log in again to receive a fresh access token. The grant increments
   `users.token_version` and revokes the account's refresh tokens, so tokens issued
   before the grant are rejected even if they have not expired.
7. Confirm the audit row and then test the docs:
   ```powershell
   $login = Invoke-RestMethod -Method Post `
     -Uri "https://<your-service>.onrender.com/api/v1/auth/login" `
     -ContentType "application/json" `
     -Body '{"identifier":"<target-email>","password":"<password>"}'

   Invoke-RestMethod -Uri "https://<your-service>.onrender.com/v3/api-docs" `
     -Headers @{ Authorization = "Bearer $($login.data.accessToken)" }
   ```
   A `200` with an `openapi` field confirms the fresh JWT carries `ROLE_ADMIN`.

**Audit verification**

The `audit_logs` row has `action = 'ADMIN_BOOTSTRAP'`, `actor_email =
'system:admin-bootstrap'`, `actor_role = 'SYSTEM'`, `entity_type = 'users'`,
`entity_id = <user id>`, and details with the verification channel, token-version
change and revoked-refresh-token count. Read it with the new ADMIN token:

```powershell
Invoke-RestMethod `
  -Uri "https://<your-service>.onrender.com/api/v1/admin/audit-logs?action=ADMIN_BOOTSTRAP" `
  -Headers @{ Authorization = "Bearer $($login.data.accessToken)" }
```

**Rollback (only with explicit approval)**

If the wrong account was selected, revoke the role and invalidate its sessions.
Take a backup first and review the statements before running them:

```sql
BEGIN;
DELETE FROM user_roles
WHERE user_id = '<user-id>'
  AND role_id = (SELECT id FROM roles WHERE name = 'ADMIN');
UPDATE users SET token_version = token_version + 1 WHERE id = '<user-id>';
UPDATE refresh_tokens SET revoked_at = now()
WHERE user_id = '<user-id>' AND revoked_at IS NULL;
COMMIT;
```

## 5. Connect the Vercel frontend

1. Vercel → Project → Settings → Environment Variables: set the API base URL to
   `https://<your-service>.onrender.com` (https, no trailing slash).
2. Redeploy the frontend.
3. In Render, set `APP_CORS_ALLOWED_ORIGINS` to the exact frontend URL, e.g.
   `https://your-app.vercel.app`. No trailing slash, no wildcard, comma-separated
   if there are several.
4. Changing an env var restarts the service; CORS is read at startup.

Preflight from the allowed origin should return `200` with
`Access-Control-Allow-Origin`, and from any other origin `403` with no CORS
headers:

```powershell
curl.exe -s -D - -o NUL -X OPTIONS `
  -H "Origin: https://your-app.vercel.app" `
  -H "Access-Control-Request-Method: POST" `
  -H "Access-Control-Request-Headers: authorization,content-type" `
  https://<your-service>.onrender.com/api/v1/auth/login | Select-String "HTTP/|access-control-"
```

## 6. Browser smoke test

Register, log in, load the games list, and open one package. A console
`blocked by CORS policy` error means step 5's origin does not match exactly
(scheme, host, port and trailing slash all matter).

Orders stay `PENDING_PAYMENT` by design: with `PAYMENT_GATEWAY=mock` and dev tools
disabled there is no way to confirm payment. That is the safe behaviour for a
public portfolio link.

## Free-tier behaviour

- The free instance sleeps after ~15 minutes of inactivity; the next request can
  take ~50 seconds. Mention this to anyone you share the link with.
- 512 MB RAM. If the container restarts with an out-of-memory kill, lower the
  heap with an additional env var:
  `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=55 -XX:+ExitOnOutOfMemoryError`,
  or move to a paid instance type.
- No persistent disk. Everything durable lives in Neon; never add a Render disk
  and expect the database there.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Deploy fails with `Could not resolve placeholder 'APP_PASSWORD_RESET_FRONTEND_BASE_URL'` | Env var missing; it has no default in `prod` |
| `Could not resolve placeholder 'SPRING_DATASOURCE_URL'` | DB variables not set (or set on the wrong service) |
| `FATAL: password authentication failed` | Wrong Neon user/password, or the old compromised password |
| `The server does not support SSL` / connection refused | Missing `?sslmode=require`, or a typo in the Neon host |
| Flyway: `unable to obtain lock` / hangs on migrate | Using the Neon `-pooler` host; switch to the direct host |
| Health check never passes, logs look healthy | App bound to a fixed port instead of `PORT`; remove any `SERVER_PORT` override and redeploy |
| First request times out after ~50 s | Free instance waking up; retry |
| Browser `CORS` error | `APP_CORS_ALLOWED_ORIGINS` does not match the Vercel origin exactly |
| `503 VERIFICATION_DELIVERY_UNAVAILABLE` | Expected: no email/SMS sender is implemented |
| Swagger UI returns `401` in the browser | Expected while `app.openapi.public=false`; inject an ADMIN bearer header (see section 4) or use curl/Postman |
| Swagger UI returns `403` | Signed in, but the account does not have `ROLE_ADMIN` |
| `/v3/api-docs` returns `404` with an ADMIN token | `SPRINGDOC_ENABLED=true` is missing on the running service, or the deploy is behind the current commit |
| Order stays `PENDING_PAYMENT` | Expected: mock gateway, dev tools disabled |

## Limitations (read before sharing the link)

- No real payments: ABA PayWay is sandbox-only and not wired to live credentials.
- No real game-credit delivery: the supplier adapter is `mock`.
- No email/SMS verification delivery: endpoints return `503` outside dev.
- No backups/monitoring: Neon holds the data; export it if you care about it.
- The public repository still contains the old `.env` in its history. Rotate
  every secret that was in it before using this deployment for anything real.
