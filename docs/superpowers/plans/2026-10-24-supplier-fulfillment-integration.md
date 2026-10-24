# Supplier selection in fulfillment

Implement the requested integration in the existing dirty checkout; preserve earlier work and leave changes uncommitted. The referenced ai-agents directory is absent. This plan extends the earlier read-only supplier-selection task.

## Design and decisions

- Reuse the deterministic lowest-cost, same-currency active mapping query, existing order lock, fulfillment and attempt entities, worker and payment after-commit hooks.
- Persist supplier ID, mapping ID, SKU and mock adapter key on the unique fulfillment for each order. Existing V1-V8 cannot store this association: add V9 without editing them. Back up and validate on staging PostgreSQL before deployment.
- Commit the selected mapping and PROCESSING/PENDING-attempt claim before calling the mock. Finish in a separate locked transaction. A concurrent caller returns the existing record. A crash after submission leaves a non-resubmittable claim.
- Null responses, transport/protocol exceptions and success without a reference are unknown, retained as PROCESSING with a PENDING attempt and order NEEDS_REVIEW. Only explicit rejection is retryable. Legacy attempted records without a selection require review.
- No eligible mapping produces FAILED with zero attempts and NEEDS_REVIEW; explicit retry can recover after configuration is corrected. Once selected, keep the same mapping/SKU and recheck supplier/mapping activity before another known-safe attempt.
- Reject real adapters. Guard refunds during unresolved delivery and block new delivery when a refund is pending or succeeded. Preserve terminal order states during result recording.

## Tasks

- [ ] Add real-database tests for absent/inactive mappings, preserved selection, unknown and crash outcomes; adapt existing concurrency, payment and worker fixtures to committed catalog data.
- [ ] Observe tests failing; implement selection snapshots, V9 and durable claim/result transactions using the existing service.
- [ ] Add and verify the refund guard required by the shortened database lock duration.
- [ ] Run focused tests and the full Maven suite; inspect migration and permissions, request independent code review, and fix material findings.

## Verification record

- Sandbox Maven dependency download was blocked; escalated Maven resolved dependencies. First compilation found the expected absent snapshot accessors in newly added tests.
