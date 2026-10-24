# Mock Supplier Fulfillment Implementation Plan

**Goal:** Complete and verify the existing mock supplier module requested in the task.

**Architecture:** Keep the existing Java 17 / Spring Boot / JPA module and V6 schema. Verified payment completion triggers synchronous mock delivery, recording one fulfillment per order and its attempts. Failures exhaust the configured attempt budget, leave the payment completed, and move the order to NEEDS_REVIEW for manual retry.

**Design choices:** Extend the existing implementation rather than add a second module or a queue. Serialize delivery and retry using a database lock on the order, including when no fulfillment exists yet. Keep the existing supplier contract and use its order number as the stable mock delivery identity. No new dependencies or real supplier calls.

**Scope:** The requested files already exist. Close gaps in concurrency, resumable-state validation, supplier reference storage, and recovery from fulfillment startup failure. Retain current property names and service methods. Work in the supplied clean checkout and leave changes uncommitted for review.

## Tasks

- [x] Add failing fulfillment tests for pending deliveries on unpaid/closed orders, oversized supplier references, concurrent initial calls and retries, and stable mock results.
- [x] Add order locking and state validation before supplier calls; bound references to the schema's 255 characters and reset stale retry metadata. Make mock references deterministic.
- [x] Test and fix payment completion retries so an already-completed payment can restart fulfillment after a transient startup failure. Cover supplier failure without reversing payment completion and provider mismatch without fulfillment.
- [x] Correct fulfillment documentation and configuration examples; run focused tests and the complete suite against isolated test databases; request an independent review.

## Review focus

- Concurrent callbacks and staff retries must share a fulfillment and avoid duplicate supplier calls.
- A PENDING fulfillment must not bypass checks on cancelled, refunded, failed, or unpaid orders.
- Supplier failures and null responses must be audited and stop at the configured budget.
- Duplicate successful-payment notifications must recover a missing fulfillment without charging again.
- Payment and fulfillment state must be inspected using committed transactions, not only transactional test fixtures.

## Verification log

- Initial checkout clean; all requested classes, V6 migration and payment hook were already present.
- Maven wrapper fails locally while indexing a null filesystem Target property. Use the installed cached Maven distribution directly without changing the wrapper.
- Offline baseline could not resolve the Spring Boot parent; online dependency resolution requested for tests.
- Baseline: 19 existing fulfillment and payment service tests passed.
- Fulfillment regressions: 16 tests ran, with 9 expected failures/errors before fixes (concurrency conflicts, invalid order status, reference overflow, stale retry metadata, random mock reference).
- Payment regressions: 9 tests ran, with 7 expected failures before fixes (five recovery paths and two ambient transaction cases).
- Transaction decision: defer fulfillment until ambient payment commit, then execute it in a new transaction. API tests now use committed calls and an isolated H2 database rather than an enclosing rollback transaction.
- Payment verification after fixes: 32 tests passed across PaymentFulfillmentTests, PaymentServiceTests and PaymentApiTests.
- Initial full-suite run under the test profile: 131 passed, one unrelated VerificationApiTests failure because verification delivery is intentionally unavailable outside dev. No authentication code was changed.
- Final full-suite run: 132 tests passed, zero failures/errors/skips. Used the dev console delivery profile for tests without their own profile, isolated H2 databases in PostgreSQL mode, Hibernate create-drop, disabled Flyway, seeded USER/ADMIN roles, and forced mock payment/supplier adapters. No live ABA or supplier transaction was performed.
- Independent read-only review: no actionable defects. Durable queues, real supplier reconciliation and network timeouts remain outside this mock scope, as documented in README.
- Final whitespace validation: git diff --check passed. Changes remain uncommitted in the supplied checkout.

## Requested structure extension

The follow-up requests FulfillmentWorker, FulfillmentResponse, SupplierTopupRequest,
and SupplierTopupResponse alongside the existing module files.

- [x] Extract the supplier request/result records into the requested top-level DTOs and update all references.
- [x] Add an immutable fulfillment response with attempt snapshots, following the existing response record convention.
- [x] Add a scheduled recovery worker that selects at most 50 eligible orders oldest-first, every 30 seconds after a 30-second initial delay. Enable by default, with an explicit off switch. Recover PAID orders with missing/PENDING fulfillment and PROCESSING orders with PENDING fulfillment. Skip exhausted, successful, and already-processing deliveries. Use the existing locked fulfillment service once per order, outside a batch transaction; continue after an individual startup failure.
- [x] Test bounded batches, recovery, invalid/terminal state exclusion, repeated scans, failure isolation, and response snapshot immutability; run the full suite and review.

Keep immediate delivery after payment commit. The worker is a recovery mechanism,
not a change to checkout timing. Existing order locks handle concurrent workers.
No new HTTP endpoint, database migration, or external supplier is required.

Extension verification: the first new-test run failed because FulfillmentWorker
did not exist. After implementation, 30/31 focused tests passed; the batch-order
test relied on wall-clock creation timestamps that could tie. Its fixtures now
use explicit distinct dates. The final complete suite passed 138 tests with zero
failures, errors or skipped tests on isolated H2 databases. Unrelated test contexts
disable the scheduler; worker tests enable the actual bean with a 24-hour initial
delay and invoke scans directly. Independent review found no actionable defects.
All 13 files requested in the follow-up tree exist, and no old nested supplier
request/result type references remain.
