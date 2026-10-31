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
| Port | Render injects `PORT`; `application-prod.yml` binds `server.port: ${PORT:${SERVER_PORT:8082}}` |
| Health check | `/actuator/health/readiness` (200 with `status: UP` only when the DB answers) |
| Profiles | `SPRING_PROFILES_ACTIVE=prod` |
| Schema | Flyway runs `V1`–`V12` on the first boot, then Hibernate validates only |
| Database | External Neon PostgreSQL (`sslmode=require`), no Render disk |
| Start command | Image `ENTRYPOINT` (`java -jar /app/application.jar`); do not override |

`render.yaml` at the repository root is an optional Blueprint. A manual
**New → Web Service** with runtime **Docker** is equivalent.

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
and `200` for the public games list. `/v3/api-docs` must return `404` (Swagger is
disabled in `prod`).

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
| Order stays `PENDING_PAYMENT` | Expected: mock gateway, dev tools disabled |

## Limitations (read before sharing the link)

- No real payments: ABA PayWay is sandbox-only and not wired to live credentials.
- No real game-credit delivery: the supplier adapter is `mock`.
- No email/SMS verification delivery: endpoints return `503` outside dev.
- No backups/monitoring: Neon holds the data; export it if you care about it.
- The public repository still contains the old `.env` in its history. Rotate
  every secret that was in it before using this deployment for anything real.
