# Supplier and fulfillment PostgreSQL coverage

`SupplierFulfillmentPostgresTests` uses the shared PostgreSQL 17 integration fixture, the applied Flyway history, and Hibernate schema validation. Orders enter through the real authenticated order API. Provider calls stay inside the local mock payment and supplier adapters; no live payment or diamond delivery occurs.

| Scenario | Required persisted behavior |
| --- | --- |
| Supplier selection | Compare costs only within the requested currency; exclude inactive mappings and suspended suppliers; break equal-cost and equal-time ties by mapping ID. |
| Repeated completed-payment events | One fulfillment, one successful attempt, one supplier call; verified payment and order completion remain separate. |
| Timeout after possible submission | Payment remains completed; fulfillment remains `PROCESSING` with one `PENDING` attempt; order requires review; repeated events and both retry entrypoints do not submit again. |
| Missing eligible mapping | Persist `FAILED` and `NEEDS_REVIEW` with zero supplier calls instead of losing the review record to a rollback-only transaction. |
| Confirmed supplier rejection | An explicit retry can succeed and retains the originally selected supplier product and SKU in the outbound request despite later catalog edits. |
| Concurrent duplicate delivery | A separate database reader sees the committed claim before the supplier returns; a competing caller returns that claim without another supplier call. |

Existing H2 tests additionally cover blank successful supplier references, failure while recording the supplier result, manual retry concurrency, worker recovery, unpaid orders, and retained payment completion. The result-storage failure fixture now throws only when saving a `SUCCESS` attempt, allowing normal repository delegation for the initial `PENDING` claim.

## Audit findings before the PostgreSQL run

The previous Surefire reports show two concurrency failures, three unknown-outcome failures, and eight supplier-selection fulfillment failures/errors. Inspection identified an uncommitted delivery claim during the supplier call, retries of unknown outcomes, absent mapping fields in the supplier request, unvalidated success references, missing retry eligibility checks, and caught supplier-selection exceptions marking the outer transaction rollback-only. One existing test also failed prematurely by invoking an abstract repository interface method through `callRealMethod()`.

Production changes are gated on the PostgreSQL reproduction. Test outcomes and any minimal repairs are reported in the overall QA report after execution. Applied migrations V1, V6 and V9 are unchanged.
