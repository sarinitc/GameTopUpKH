# Bay2Game read-only supplier integration

Documentation reviewed on 2026-10-27: https://bay2game.com/developer_docs/ (including its inline endpoint definitions and generated GET examples).

The documentation specifies base URL `https://api.bay2game.xyz/api`, with `api_key` as a URL-encoded query parameter. It does not document Bearer or X-API-Key authentication. The implementation pins that HTTPS base URL and permits only these provider reads:

| Provider request | Local admin endpoint |
| --- | --- |
| GET `/profile` | GET `/api/v1/admin/suppliers/bay2game/profile` |
| GET `/profile`, project balance | GET `/api/v1/admin/suppliers/bay2game/balance` |
| GET `/categories` | GET `/api/v1/admin/suppliers/bay2game/categories` |
| GET `/products` with `game_code` | GET `/api/v1/admin/suppliers/bay2game/products?gameCode=freefire_sgmy` |

Local requests use the existing admin JWT authentication. Never send a supplier credential from a browser or pass it as a local request parameter. Results are allowlisted DTOs in `ApiResponse` with `Cache-Control: no-store`. Product prices are decimal USD values. The documentation does not specify profile balance currency, so the balance response reports `currency: null`. `fetchedAt` is UTC; provider timestamps lack a timezone and are not converted or exposed.

## Verification status and enablement

The host and authentication format above are confirmed **from documentation only**. Provider-support confirmation and installation of a newly rotated key have not been verified. No authenticated request was made during implementation. Keep the integration disabled until both prerequisites are complete.

The documentation lists support at https://t.me/B2GameSupport and support@bay2game.com. Obtain a non-secret support reply confirming:

1. `https://api.bay2game.xyz/api` is the approved host for this merchant, and GET query parameter `api_key` is the required authentication format for the three reads (or whether a safer header format is officially supported).
2. Rotation has revoked the old key, the replacement is authorized for these reads, and any IP allowlist requirements are satisfied.
3. Profile balance/total_spent denomination, timestamp timezone, rate limits and response limits.
4. Whether a separate, non-chargeable Free Fire nickname lookup exists for `freefire_sgmy`, with its official method, endpoint, auth, request/response contract, region coverage and error semantics.

Do not include keys or player credentials in a support message. No support message was sent by this implementation.

After confirmation, inject the rotated key into the **server process environment** as `BAY2GAME_API_KEY` using the deployment secret mechanism. The code reads `System.getenv` directly; a Spring property, imported `.env`, command-line property or database value cannot supply it. Do not paste keys into chat, shell command history, source, screenshots or log output. Restart the server after key rotation. The application cannot independently attest that a supplied key was newly rotated.

Set non-secret server settings:

```text
BAY2GAME_ENABLED=true
BAY2GAME_PROVIDER_CONFIRMED=true
BAY2GAME_CONNECT_TIMEOUT=3s
BAY2GAME_READ_TIMEOUT=10s
SUPPLIER_CLIENT=mock
```

`BAY2GAME_PROVIDER_CONFIRMED` is an operator assertion about the exact documented host/auth contract, not an automated provider verification. Enablement without confirmation fails startup. Missing/blank/whitespace-bearing keys fail with a fixed message that excludes the value. Disabled mode creates no outbound client and returns `503 BAY2GAME_DISABLED` to authorized admins.

The dedicated HTTP client has no application tracing/interceptors, never follows redirects and performs no application retries. Because the provider requires a query credential, keep HTTP wire/debug logging and full-URL capture disabled in the JVM, proxies, APM and infrastructure; redact `api_key` before recording any outbound URLs. Do not enable `jdk.httpclient.HttpClient.log` or JDK HTTP transport logging for this client. A provider-supported header alternative needs a confirmed contract before changing authentication.

## Boundaries and failure behavior

- No orders, deposits, callbacks, automatic synchronization or local supplier/product mutations are implemented. Do not use the documented `/create_order` endpoint for verification: it deducts funds even via GET.
- `MockSupplierClient`, fulfillment registry/selection, payments and player verification remain unchanged. Upstream products are not imported into `supplier_products`; existing mappings affect delivery selection and require separate review.
- Free Fire nickname verification is **not documented and not confirmed supported**. The category's `userid` field describes top-up input, not a nickname lookup. Existing non-mock player lookup continues returning `UNAVAILABLE`; no endpoint or nickname is invented.
- Provider authentication rejection maps to `502 BAY2GAME_AUTH_FAILED`; 429 to `503 BAY2GAME_RATE_LIMITED`; transport failures to `503 BAY2GAME_UNAVAILABLE`; other unsuccessful HTTP responses to `502 BAY2GAME_UPSTREAM_ERROR`; malformed/incomplete/over-1-MiB responses to `502 BAY2GAME_INVALID_RESPONSE`; provider non-success status to `502 BAY2GAME_REJECTED`.
- Raw provider bodies, URLs and exception causes are discarded. Invalid game codes return `400 INVALID_GAME_CODE` before outbound access. A successful empty category/product list is accepted; missing lists are errors.
- There is no stale-cache fallback or automatic retry. Operators may repeat a read after the underlying issue is resolved.

Rollback: set `BAY2GAME_ENABLED=false` and restart. No migration, database rollback or backup is required for this additive read-only code; no applied Flyway file changed.

## Automated verification

`Bay2GameClientTests` uses MockRestServiceServer with fabricated credentials and documented-shaped responses. `Bay2GameConfigurationTests` checks disabled mode and unsafe configuration. `Bay2GameApiTests` exercises real Spring security and controller behavior with an isolated H2 database and a mocked client. None requires a real Bay2Game credential or contacts the provider.

```text
mvn test -Dtest=Bay2GameClientTests,Bay2GameConfigurationTests,Bay2GameApiTests
```

The repository Maven wrapper failed to start in this environment. A cached Maven 3.10.0 distribution is used for verification; see the final implementation report for exact test results.
