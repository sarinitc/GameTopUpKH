# Docker Production Deployment

Runbook for deploying the GameTopUp KH backend as a Docker Compose stack on a
single Linux host. It covers preparation and local verification only; publishing
requires a destination, a domain, and TLS, which are operator decisions.

## What this deploys, and what it does not

| Included | Not included (launch blockers) |
| --- | --- |
| Spring Boot API behind a host reverse proxy | Real payment capture (ABA PayWay is sandbox-only) |
| Private PostgreSQL 17 with a named volume | Real diamond delivery (supplier adapter is `mock`) |
| Flyway migrations applied at startup | Real email/SMS verification codes (`503` in prod) |
| Readiness-based health checks | Browser CORS support (not configured yet) |
| Non-root container, loopback-only port | Monitoring, alerting, off-host backups |

**Until the blockers are cleared, this deployment is a staging-style environment:
it accepts orders and records payments, but it cannot collect real money or
deliver real game credit.**

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Multi-stage build; Java 17 JRE runtime, non-root user, readiness healthcheck |
| `.dockerignore` | Keeps `.env`, `target/`, `docs/` and logs out of the build context |
| `docker-compose.prod.yaml` | App + private Postgres, health-gated startup, restart policy, log rotation |
| `src/main/resources/application-prod.yml` | `ddl-auto=validate`, dev tools off, Swagger off, graceful shutdown |
| `.env.production.example` | Documented template with no real values |
| `docker-compose.yaml` | Unchanged development database only |

## Prerequisites

- Linux host with Docker Engine 24+ and the Compose v2 plugin
- 2 vCPU / 2 GB RAM minimum (the JVM is capped at 75% of the container memory)
- A TLS-terminating reverse proxy (Caddy, nginx, or Traefik) on the same host
- A DNS name pointing at the host, because the payment callback URL must be HTTPS

## 1. Rotate secrets first

This repository is **public** and `.env` has been committed and pushed. Treat
these values as compromised and rotate them before any deployment:

| Secret | Where to rotate |
| --- | --- |
| `JWT_SECRET` | Generate a new value; every session is invalidated |
| `POSTGRES_PASSWORD` / `DB_PASSWORD` | New database password (and re-encrypt if the DB was reused) |
| `ABA_PAYWAY_API_KEY`, `ABA_PAYWAY_MERCHANT_ID` | ABA PayWay merchant dashboard |
| `BAY2GAME_API_KEY` (if ever set) | Supplier support channel |

Then remove the file from tracking (the local copy is kept):

```bash
git rm --cached .env
git commit -m "Stop tracking local environment file"
```

Removing the file from the working tree stops future leaks but does **not**
remove it from history. Because the repository is public, also purge history
(`git filter-repo --path .env --invert-paths`, then force-push) or open a GitHub
support request to invalidate cached views. Rotation is mandatory regardless;
history rewriting only limits further exposure.

## 2. Prepare the host environment file

Keep the real file outside the repository and readable only by root:

```bash
sudo install -m 600 /dev/null /etc/gametopup/production.env
sudo editor /etc/gametopup/production.env
```

Start from `.env.production.example` and fill in every required value. Compose
refuses to start when `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
`JWT_SECRET`, or `APP_PASSWORD_RESET_FRONTEND_BASE_URL` is missing, and the error
message never prints the value.

## 3. Build and start

```bash
cd /opt/gametopup
sudo docker compose --env-file /etc/gametopup/production.env \
  -f docker-compose.prod.yaml up -d --build
```

Startup order is enforced: the app waits for `pg_isready` before booting, then
Flyway applies any pending migrations (`V1`–`V12` today) and Hibernate validates
the mappings without altering the schema.

### Existing database

Before a release that adds migrations, take a backup (see below). Migrations are
not automatically reversible and `ddl-auto=validate` will refuse to start if the
schema and entities disagree.

## 4. Verify

```bash
# Container and health status
sudo docker compose -f docker-compose.prod.yaml ps
sudo docker inspect --format '{{.State.Health.Status}}' gametopup-prod-app

# Anonymous readiness probe (status only, no details)
curl --fail --silent http://127.0.0.1:8082/actuator/health/readiness

# Swagger must be absent in prod (expect 401/404, not 200)
curl --silent -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/v3/api-docs

# Development routes must not exist (expect 404)
curl --silent -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8082/api/v1/dev/payments/00000000-0000-0000-0000-000000000000/success

# The database must not be reachable from the host (expect connection refused)
nc -z 127.0.0.1 5432 || echo "not exposed"
```

Then confirm a real flow: register, log in, `GET /api/v1/games`, create an order,
and read it back. Payment confirmation is only possible with a configured gateway;
with the mock gateway and dev tools disabled, orders stay in `PENDING_PAYMENT` —
that is the intended safe behaviour.

Logs:

```bash
sudo docker compose -f docker-compose.prod.yaml logs -f --tail=100 app
```

Secrets never appear in logs, but do not paste full log excerpts into public
issues without checking for request headers and identifiers.

## 5. TLS reverse proxy

The API listens on `127.0.0.1:8082` only. Put a proxy in front of it; example
with Caddy (`/etc/caddy/Caddyfile`):

```caddyfile
shop.example.com {
    reverse_proxy 127.0.0.1:8082
}
```

Caddy obtains and renews certificates automatically and forwards
`X-Forwarded-*`, which `server.forward-headers-strategy: framework` consumes.
The payment callback URL in `.env.production` must match the public HTTPS
address, for example
`https://shop.example.com/api/v1/payments/aba/callback`.

## 6. Backup and restore

```bash
# Backup (custom format, compressed)
sudo docker compose -f docker-compose.prod.yaml exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "gametopup-$(date +%F-%H%M).dump"

# Restore into a running, empty database
sudo docker compose -f docker-compose.prod.yaml exec -T postgres \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < backup.dump
```

Store dumps off the host. Copy the volume itself only with the stack stopped:
`docker run --rm -v gametopup-prod-postgres-data:/data -v "$PWD":/backup alpine tar czf /backup/volume.tgz /data`.

**Never run `docker compose ... down -v` against production** — it deletes the
database volume.

## 7. Rollback

1. Re-deploy the previous image tag: set `APP_IMAGE_TAG` to the tag that worked
   and run `docker compose ... up -d` (no `--build`, no `-v`).
2. If the failure came from a migration, restore the backup taken before the
   release. Application rollback alone does not revert schema changes.
3. Confirm `pg_isready`, readiness `UP`, and a basic catalog request after the
   rollback.

Tag images per release (`APP_IMAGE_TAG=2026-11-01`) and keep at least one
previous tag on the host.

## 8. Operating constraints

- **Single instance.** The fulfillment worker and reconciliation jobs assume one
  app process. Scale horizontally only after adding distributed locking.
- **Graceful shutdown.** `stop_grace_period: 45s` matches the 30 s Spring
  shutdown phase; `docker compose stop` finishes in-flight requests.
- **No host database port.** Admin access goes through
  `docker compose -f docker-compose.prod.yaml exec postgres psql -U <user> -d <db>`.
- **Log rotation.** JSON logs are capped at 10 MB × 3 (Postgres) and 10 MB × 5
  (app). Ship them elsewhere if longer retention is required.
- **CORS is not configurable yet.** Browser clients on another origin receive no
  CORS headers and their requests fail. The `APP_CORS_ALLOWED_ORIGINS` variable
  is reserved for the pending security change; it currently has no effect.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `app` exits immediately | Missing required env var, or `SPRING_PROFILES_ACTIVE` not `prod` |
| `Validate failed: migrations are out of order` | Applied migration edited or a new migration numbered below the latest |
| Readiness stays `OUT_OF_SERVICE` | Database unreachable; check `pg_isready` and `SPRING_DATASOURCE_URL` hostname (`postgres`, not `localhost`) |
| `503 VERIFICATION_DELIVERY_UNAVAILABLE` | Expected in prod until an email/SMS sender is implemented |
| Order stays `PENDING_PAYMENT` | Expected with the mock gateway and dev tools disabled |

## Definition of done for a real release

- [ ] All secrets rotated and history purged
- [ ] Backups automated and restored at least once on a staging copy
- [ ] TLS proxy serving HTTPS with a valid certificate
- [ ] Real payment gateway approved, callbacks verified, amount/currency checks tested
- [ ] Authorized supplier adapter replaces `mock`, with idempotent delivery verified
- [ ] Email/SMS sender implemented, or contact verification removed from the UI
- [ ] CORS restricted to the exact storefront origin
- [ ] Monitoring and alerting outside the container
