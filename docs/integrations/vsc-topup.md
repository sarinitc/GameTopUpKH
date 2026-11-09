# VSC Games top-up adapter (Free Fire)

Documentation reviewed: supplier-provided VSC Games instructions (as quoted by the store owner):
`GET https://api.vscgames.com/freefire-merchant-topup` with query parameters
`productId`, `playerID`, `Merchantname` and a `merchantkey` header. Response:
`{"code": 200, "message": "...", "orderId": "...", "status": "Completed", "amount": 1.5}`.
A top-up counts as delivered only when `code == 200` **and** `status == "Completed"`.
The supplier answers HTTP 400 when `playerID` is not numeric.

This adapter is **not** the Bay2Game integration and is **not a verified production supplier**.
It is implemented fail-closed: no outbound call is possible until every enablement gate is set
explicitly, and it stays out of every default configuration.

## Module layout

```text
supplier/integration/vsc/
├── VscSupplierClient.java   # SupplierClient adapter: validation, HTTP call, fail-closed result mapping
├── VscProperties.java       # supplier.vsc.* settings, validated at startup (non-secret only)
└── VscConfiguration.java    # bean wiring: dedicated RestClient, env-only merchantkey, no retries
```

## Contract and behaviour

| Element | Behaviour |
| :--- | :--- |
| Method / path | `GET /freefire-merchant-topup` (host pinned in code: `https://api.vscgames.com`) |
| Params | `productId=<numeric>`, `playerID=<numeric>`, `Merchantname=<configured name>` (URL-encoded) |
| Header | `merchantkey: <key>` — lower-cased exactly as documented; never logged |
| Local validation | `playerID` and `productId` must match `^[0-9]{1,20}$` (checked before any outbound call, so the supplier's 400 is never triggered by us); `quantity` must be `1`; merchant name must be non-blank, ≤100 chars, no control characters |
| Success rule | HTTP 2xx **and** JSON `code == 200` (integral) **and** `status` (trimmed, case-insensitive) `== "Completed"`; provider `orderId` becomes the fulfillment `supplierReference`; provider `amount` is validated (number, ≥ 0) but **never used** — order amounts come from the database |
| Non-success | All HTTP 4xx/5xx, transport failures, malformed/oversized (>1 MiB)/non-object bodies, missing/invalid `code`, unknown or non-`Completed` `status`, missing negative amount → `SupplierException` → fulfillment recorded as **outcome requires review**, never auto-retried |
| Retries | None. GET with side effects is not assumed idempotent; the fulfillment state machine never retries a `SupplierException` |
| Failures that return `SupplierTopupResponse.failure` | Only local, deterministic, non-chargeable rejections (bad player ID, bad SKU, quantity ≠ 1) |

## Enablement gates (all required)

```text
supplier.vsc.enabled=true            # creates the client bean (env: VSC_ENABLED)
supplier.vsc.provider-confirmed=true # NBC-style confirmation of the documented contract (env: VSC_PROVIDER_CONFIRMED)
SUPPLIER_CLIENT=vsc                  # selects the adapter for fulfillment (env: SUPPLIER_CLIENT)
SUPPLIER_VSC_API_KEY=<rotated key>   # process environment ONLY; direct System.getenv, never YAML/DB/CLI
```

`enabled=true` without `provider-confirmed=true` fails startup. `SUPPLIER_CLIENT=vsc` without
`VSC_ENABLED=true` fails fast in the registry. Mock remains the default everywhere
(`supplier.client=mock`, `supplier.vsc.enabled=false`).

Non-secret settings (`VSC_MERCHANT_NAME` default `GameTopUp-KH`; `VSC_CONNECT_TIMEOUT=3s`;
`VSC_READ_TIMEOUT=10s`) are validated at startup. Set `VSC_MERCHANT_NAME` to the exact shop
name registered with the provider.

## What is confirmed vs not confirmed

**Confirmed (from the supplier's own instructions):** HTTP method, host, path, header name,
parameter names, `code == 200` + `status == "Completed"` success rule, numeric `playerID`
requirement, `400` rejection behaviour.

**Not confirmed — do not enable until resolved with the provider:**
- Whether repeating the GET re-executes the top-up (GET with side effects). There is no documented
  idempotency key or `Merchantname`-scoped dedup. The adapter does no retries and treats every
  ambiguous outcome as requiring review, but the provider must confirm this before live money moves.
- The vocabulary of failure `status` values and/or `code` values (e.g. insufficient balance), and
  whether any of them are definitive/terminal. Until then everything except `Completed` requires
  manual review rather than automatic retry or automatic FAILED.
- Whether `productId` is exactly the numeric ID this store expects, per game/package, and how it
  maps to our `supplier_products.external_sku` (it must be the numeric ID, ≤20 digits).
- Balance/deduction semantics, currency of `amount`, rate limits, and IP allowlisting.
- Whether an agreement/approval is required for top-ups via this endpoint at this volume.
- Whether order status is queryable after the fact (no documented status-lookup endpoint was
  provided) — needed if a timeout must be reconciled later.

## Canary procedure (only after all prerequisites are confirmed)

1. `SUPPLIER_CLIENT=mock` everywhere; confirm health readiness is UP.
2. Enable only the adapter, with provider confirmation: `VSC_ENABLED=true`, `VSC_PROVIDER_CONFIRMED=true`. It serves no traffic until selected.
3. Add one test supplier row: `supplier.name=vsc-games`, `adapterKey=vsc`, and one `supplier_products` row for the lowest-value Free Fire package with `external_sku=<numeric productId>`.
4. Select it: `SUPPLIER_CLIENT=vsc`. Watch startup logs for `VSC` selection.
5. Place one internal order (own account, lowest-value package), let payment verify as usual, and observe the fulfillment. Expect `SUCCEEDED` with `supplierReference=<VSC orderId>`, or `UNKNOWN` requiring review — never an automatic retry.
6. If anything diverges: set `VSC_ENABLED=false` and `SUPPLIER_CLIENT=mock`, restart, and keep the VSC order for provider-support investigation.

## Rollback

Set `VSC_ENABLED=false` and `SUPPLIER_CLIENT=mock`, restart. No migration, no database change; the
adapter is purely additive. Supplier products rows can stay — they are inert while the mock client
is selected.

## Verification

```text
mvn test -Dtest='Vsc*'
mvn clean verify
```
