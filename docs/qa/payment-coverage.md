# Payment, reconciliation and refund coverage

`PaymentPostgresTests` runs the HTTP security layer, payment service, real ABA gateway/response mapper,
refund service, fulfillment service and repositories against disposable PostgreSQL 17. Flyway creates
the schema and Hibernate validates it; tests do not use `ddl-auto=create` or an enclosing rollback
transaction. `AbaPayWayClient` is mocked before transport; the supplier is the local mock. No ABA,
supplier or email requests are sent, and no real merchant credentials are required.

| Area | PostgreSQL assertions |
| --- | --- |
| QR creation | Reservation exists before transport; repeated creation returns the same payment; overlapping creation cannot send a second provider request; QR alone leaves the order pending. |
| Uncertain creation | Provider failure preserves a durable `NEEDS_REVIEW` payment and prevents another charge. |
| Callback trust | A callback claiming approval cannot override provider `PENDING`; authoritative status is read through the real ABA mapper. |
| Verification | Callback and admin reconciliation reject wrong amount, currency or reference, and missing amount/currency; no paid history or delivery is created; the original provider reference is preserved. |
| Duplicate callbacks | Repeated and simultaneous callbacks produce one paid transition, one keyed event and one successful supplier attempt. |
| Provider failure | A failed lookup keeps payment/order pending, records the error without consuming the event key, and permits that event to retry. |
| Reconciliation | Requires admin; writes an actor-linked event; batch isolates a failed provider lookup and excludes recent payments. |
| Ownership | Unrelated customers cannot create/read another customer's payment or read their refunds; anonymous payment reads are rejected. |
| Refund request | ABA full/partial refunds remain pending with exact numeric amounts; requests never claim provider success or mark the order refunded; repeated and simultaneous requests create one refund. |
| Refund safety | Non-admin mutation, excessive amount, and unresolved supplier delivery are rejected without creating a refund. |

Existing H2 tests retain useful fast coverage for transaction rollback/after-commit delivery, late
pending responses after completion, active-account checks, cancellation, reconciliation cooldown,
mock refunds and refund ownership. `PaymentApiTests`, `PaymentServiceTests` and
`PaymentReconciliationServiceTests` now create eligible supplier mappings using the existing
`SupplierTestFixtures`; their completion assertions are unchanged. Previously these fixtures had
no mapping (or a nonexistent package), so fulfillment could not satisfy their expected result.

`AbaPayWayOfficialApiTests` and `AbaPayWayAdapterTests` use `MockRestServiceServer` for request
serialization, request hashes, official/legacy response parsing and transport failure coverage.
`AbaPayWayCallbackVerifierTests` covers missing/invalid/accepted signatures. These tests validate
local behavior; they do not certify a live PayWay merchant integration or the provider's current
contract.

## Findings and limits

The new reference mismatch regressions target a concrete defect: payment completion previously
compared amount/currency only and replaced the stored gateway reference before checking its
identity. A response for an unrelated payment with the same amount/currency could therefore pay
the order. The regression must reject that response through both callback and reconciliation paths.

The existing unknown-delivery test correctly requires that a supplier transport exception leave
one unresolved attempt, not trigger another delivery. Fulfillment owns that correction and its
dedicated PostgreSQL coverage. No applied Flyway migration needs editing for the payment tests.

The overlapping-create test forces the second request to arrive while the first provider call is
in progress. Callback and refund tests also execute two threads against committed rows. These are
bounded correctness regressions, not a load test or proof of every multi-instance failure scenario.

Validation commands and their actual outcomes are recorded in the overall QA report. Do not treat
the presence of this test inventory as evidence that a suite has passed.
