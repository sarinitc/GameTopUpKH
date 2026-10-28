# Bay2Game read-only integration implementation plan

> For agentic workers: use superpowers:executing-plans to execute and verify each task.

**Goal:** Add admin-only supplier profile, balance, categories and per-game product reads without enabling delivery.

**Architecture:** A separate Bay2Game client under `supplier/integration/bay2game` never implements the fulfillment `SupplierClient`. A dedicated admin controller returns allowlisted DTOs in the existing `ApiResponse`. No persistence or migration is required.

**Tech stack:** Existing Java 17, Spring Boot 4, RestClient, Jackson 3, JUnit, MockRestServiceServer, MockMvc.

**Spec:** User request on 2026-10-27 and repository `AGENTS.md`. Referenced ai-agents standards/roles are absent. Existing working-tree changes must be preserved.

## Contract and implementation decisions

- Documentation reviewed at https://bay2game.com/developer_docs/ on 2026-10-27, including inline endpoint definitions and generated request examples.
- Documented base URL: `https://api.bay2game.xyz/api`. GET query authentication: `api_key`. Only `/profile`, `/categories`, `/products` are allowed; products also require `game_code`.
- Provider-support confirmation and newly rotated key provisioning remain pending. No authenticated provider request is authorized by this plan until those prerequisites are verified.
- Separate read-only client chosen because existing SupplierClient.fulfill performs delivery; catalog import is excluded because mapped supplier products participate in fulfillment selection.
- Secret is read directly from server process environment `BAY2GAME_API_KEY`, never from Spring configuration files, request parameters, stored supplier records, or frontend.
- Default disabled; explicit enablement requires operator confirmation of the host/auth contract. Pin the documented HTTPS host and disable redirects, automatic retries, and client observations that could record query credentials. Apply finite connect/read timeouts and bounded response reads.
- DTOs use BigDecimal. Products are documented USD; profile currency and naive provider timestamp timezone are not specified, so do not invent them. Exclude undocumented timestamp conversions and return a UTC fetchedAt timestamp.
- Free Fire category `freefire_sgmy` accepts userid, but no nickname lookup endpoint appears in the published endpoint list. Support is unconfirmed; keep player verification fallback unchanged.

## Review focus

- Query credentials must not appear in diagnostics, error causes, HTTP observations, or redirect destinations.
- HTTP 200 with ERROR, missing fields, malformed or oversized payloads must fail closed.
- Authentication and rate-limit failures must be sanitized without retrying.
- Unknown game codes and malformed query input must not select arbitrary URLs or order endpoints.
- Anonymous/non-admin requests must not invoke outbound HTTP; default-disabled mode must not use a key.

## Tasks

1. Add `Bay2GameClient`, response records, configuration/properties and mocked HTTP tests in `supplier/integration/bay2game`. Test documented responses, exact auth encoding, monetary precision, failures, timeouts, redirects and configuration gates. Run tests before and after implementation.
2. Add `supplier/controller/Bay2GameController` for GET `/api/v1/admin/suppliers/bay2game/{profile,balance,categories,products}`; products require `gameCode`. Test security, validation, disabled mode, safe errors and absence of mutation routes with MockMvc.
3. Add non-secret enablement settings to application.yml, environment example comments and an operator guide with support-confirmation questions, rotation instructions and disabled rollback. Never install/read a real key during tests.
4. Run focused Bay2Game tests plus supplier/fulfillment/player regression tests; run the broader suite if practical. Independently review the final diff and report exact results and any environment limitations. Do not commit unrelated user changes.
