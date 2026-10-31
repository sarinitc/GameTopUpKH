# Production Docker Preparation Implementation Plan

> **For agentic workers:** Use the execution and verification skills for each scoped task. Steps use checkbox syntax for tracking.

**Goal:** Prepare and verify this existing backend for Docker deployment, without claiming a remote deployment or enabling live commerce before its prerequisites exist.

**Architecture:** A Java 17 image runs the existing Spring Boot JAR as a non-root user. A separate production Compose file runs the backend and private PostgreSQL with persistent storage, readiness checks, and restart policies. A host HTTPS reverse proxy connects to the backend's loopback port; actual server and domain selection remain user inputs.

**Tech Stack:** Existing Java 17, Spring Boot, Maven, PostgreSQL 17, Flyway, Docker Compose.

**Spec:** The user's request to deploy this project, following the Docker deployment discussion. Implement the preparation already described; actual publication requires the user-provided destination. Use the current checkout and preserve unrelated work in AuthController.java.

## Global Constraints

- No changes to applied migrations, money state transitions, or supplier delivery contracts.
- No credentials in the image, source, command output, or documentation. Existing tracked .env must be removed from tracking while retained locally; history rotation remains an operator requirement.
- Use prod only, never dev plus prod. Disable dev routes, live payments, and automatic fulfillment in the supplied deployment configuration.
- Mock delivery is not live commerce. Production email/SMS delivery and an authorized fulfillment adapter are missing and must be documented as launch blockers.
- Keep the existing development Compose workflow intact; use docker-compose.prod.yaml separately.
- No remote destination has been provided. Do not create hosting accounts, publish, or purchase infrastructure.

## Review Focus

- Unapproved browser origins, empty origin lists, wildcard origins, and preflight requests must not bypass authentication or ownership.
- Containers must not connect to localhost for PostgreSQL or publish a database host port.
- Missing secrets must fail Compose validation; validation output must not reveal real env contents.
- Production profile must not activate development routes or allow Hibernate schema updates.
- Backup/restore and migration compatibility must be considered before a release or rollback.

## Task 1: Browser access

**Files:** SecurityConfig.java, a focused CORS configuration if needed, application.yml, and CORS security tests.

- [ ] Test allowed-origin Authorization/Content-Type preflight, rejection of other origins, default deny, wildcard rejection, and unchanged protected API authentication.
- [ ] Run those tests before implementation and confirm the expected failures.
- [ ] Implement exact origin configuration via APP_CORS_ALLOWED_ORIGINS (comma-separated, empty default); support GET, POST, PUT, PATCH, DELETE, OPTIONS, Authorization, Content-Type, and Idempotency-Key. Use bearer-token requests without credentialed cookies.
- [ ] Run focused tests and record results.

## Task 2: Docker runtime and operating guide

**Files:** Dockerfile, .dockerignore, docker-compose.prod.yaml, .env.production.example, docs/deployment/docker-production.md.

- [x] Build a Java 17 JAR image without copying .env or secrets; run non-root, expose port 8082, provide readiness checking.
- [x] Compose backend plus private postgres:17 with a dedicated named volume, health dependencies, restart policy, bounded logs, and loopback-only backend publication.
- [x] Pass only explicit runtime settings. Require database password, JWT secret, frontend URL, and allowed origins. Disable live payments and automatic fulfillment.
- [x] Document configuration, verification, HTTPS proxy, sandbox limitations, remote deployment prerequisites, backups, and rollback without destructive volume deletion.
- [x] Validate Compose using only dummy environment values, build the image, and smoke-test against disposable local data when Docker access is available.
  - Only `APP_CORS_ALLOWED_ORIGINS` is reserved, not required: no CORS configuration exists yet (Task 1).

## Task 3: Production settings and repository hygiene

**Files:** application-prod.yml, .gitignore.

- [x] Add explicit production datasource requirements, Flyway enabled, ddl-auto=validate, open-in-view=false, and dev tools disabled.
- [x] Repair corrupt ignore rules; ignore local env/secrets, retaining example files.
- [x] Untrack .env while retaining the local file; do not rewrite history or print credentials.
  - Repository is public (`github.com/sarinitc/GameTopUpKH`) and `.env` is visible in history: rotation is mandatory and history purge is recommended in `docs/deployment/docker-production.md`.

## Task 4: Verification and review

- [ ] Run the complete Maven verification suite with Docker available for isolated PostgreSQL tests; report failures honestly.
- [ ] Run git diff --check and review the complete changes independently.
- [ ] Record build, Compose, smoke-test and review outcomes below.

## Execution record

- Initial inspection: Dockerfile is an Ubuntu/top placeholder; Compose currently starts only development PostgreSQL; CORS absent; default profile dev; prod mail/SMS senders unavailable; fulfillment mock-only; .env tracked with corrupt ignore rule.
- Docker engine access succeeds outside the restricted sandbox (28.1.1). No remote host is selected.
- Tasks 2 and 3 executed. Verified: `mvn -DskipTests package` builds one executable JAR; `docker build` succeeds (335 MB image); `docker compose --env-file <dummy> -f docker-compose.prod.yaml config` passes with dummy values and fails with an explicit message when `JWT_SECRET` is absent; the full `docker compose ... up -d --build` stack starts with the app gated on `pg_isready`, applies all 12 Flyway migrations, and returns 200 on `/actuator/health/readiness`, 404 on `/v3/api-docs`, `/swagger-ui.html` and the dev payment route, 401 on `/actuator/metrics`, with `ReadonlyRootfs=true`, `User=10001:10001` and the API published on `127.0.0.1:18082` only. Startup aborts when `JWT_SECRET` is missing.
- Defects found and fixed during smoke testing: Hikari `connection-timeout`/`validation-timeout` are milliseconds, not durations (the first `10s`/`5s` values prevented startup); the Compose `app` service was missing its `build:` section, so `up --build` attempted a registry pull instead of a local build.
- Full suite: `mvn test` ran 508 tests with 0 failures, 0 errors, 0 skipped.
- Remaining: Task 1 (CORS) and Task 4 (diff check and independent review). The existing `MockRouteIsolationPostgresTests` still activates a profile named `production`, which has no configuration file; only `prod` is configured.
