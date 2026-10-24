# Supplier Management Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Track completed steps below.

**Goal:** Implement the approved supplier module and five supplier-product endpoints.

**Architecture:** Flat supplier entities, repositories, transactional services and REST DTOs reuse the existing schema. Integration adapters move into supplier.integration; a registry selects the configured adapter. Fulfillment remains mock-based.

**Tech Stack:** Java 17, Spring Boot, JPA, Jakarta Validation, Flyway/PostgreSQL; JUnit/MockMvc/H2 for tests.

**Spec:** `docs/superpowers/specs/2026-10-23-supplier-management-design.md` (approved by user).

## Global constraints

- All management operations require ADMIN; use ApiResponse envelopes.
- Preserve the existing suppliers and supplier_products schema; no new migration.
- Preserve unrelated uncommitted work and leave this work uncommitted in the supplied checkout.
- Preserve payment/reconciliation behavior and stable mock delivery references.
- No live supplier or payment requests.

## Review focus

- Validation groups must include length, precision and currency constraints.
- Duplicate SKU races return 409 rather than leaking a database error.
- Updating SKU/cost cannot silently reassign supplier/package or change status.
- Disabling mappings remains possible when their parents are inactive.
- Registry selection uses stable adapter keys, rejects unknown/duplicate keys, and retains fulfillment testability.

## Task 1: Supplier management

Files: supplier/Supplier.java, SupplierStatus.java, SupplierRepository.java,
SupplierRequest.java, SupplierResponse.java, SupplierService.java,
SupplierServiceImpl.java, SupplierController.java. Move the existing enum.

Interfaces: SupplierRepository extends JpaRepository<Supplier, UUID>, exposes
findAllByOrderByCreatedAtDesc(), findAllByStatusOrderByCreatedAtDesc(status),
findByIdForUpdate(UUID) using a pessimistic write lock. Entity getters/setters:
id, name, status, createdAt, updatedAt. Services return response records.

- [x] Write MockMvc tests for supplier creation/default INACTIVE, explicit ACTIVE, list/filter/get, rename, status, missing resource, invalid names/status and ADMIN enforcement.
- [x] Run new API tests and verify missing routes fail with 404.
- [x] Implement the eight files following the approved design and existing catalog conventions. Status updates use findByIdForUpdate. Validation groups extend Default.
- [x] Verify supplier cases pass and inspect the diff for schema/API consistency.

## Task 2: Supplier-product mappings

Files: supplier/SupplierProduct.java, SupplierProductRepository.java,
SupplierProductRequest.java, SupplierProductResponse.java,
SupplierProductService.java, SupplierProductServiceImpl.java,
SupplierProductController.java; tests in supplier/SupplierManagementApiTests.java.

Interfaces: service create(request), findAll(supplierId, packageId, active),
findById(id), update(id, request), updateStatus(id, request), returning response
records/lists. Reuse Task 1 entity/repository and catalog GamePackageRepository.

- [x] Test mapping lifecycle, combined filters, case-preserved trimmed SKU, zero cost, default USD, retained currency, immutable parents/status, duplicate SKU create/update and per-supplier uniqueness.
- [x] Test invalid fields (blank/overlong SKU, negative/oversized/overprecision cost, malformed currency), missing parents/IDs, inactive supplier/package, disabling after suspension, and role restrictions.
- [x] Implement JPA mapping to existing table and unique constraint; persist with saveAndFlush and translate only the duplicate-SKU constraint to 409. Lock supplier for create/enable and mapping for updates.
- [x] Verify all API scenarios, then add a focused service test for database-detected uniqueness conflicts and unexpected integrity errors.

## Task 3: Adapter relocation and registry

Files: move fulfillment/supplier/{SupplierClient,MockSupplierClient,SupplierTopupRequest,SupplierTopupResponse}.java to supplier/integration; create SupplierClientRegistry.java. Modify FulfillmentServiceImpl and affected test imports; move mock tests under supplier/integration.

Interfaces: stable SupplierClient.key() with mock key "mock";
SupplierClientRegistry(List<SupplierClient>, String configuredKey),
getClient(String key), getConfiguredClient(). Duplicate/unknown keys fail explicitly.

- [x] Add registry tests for configured/default mock, unknown and duplicate keys before implementing registry; confirm red.
- [x] Move existing classes, preserve mock behavior and use registry in fulfillment. Adapt constructor tests and mock bean fixtures without test-only branches in production code.
- [x] Run registry/mock/fulfillment/payment/reconciliation regression tests.

## Task 4: Documentation and final verification

- [x] Update README with API tables, JSON examples, validation/status rules, adapter locations and routing limitation.
- [ ] Run full Maven suite on isolated H2 databases with dev console verification, seeded roles, mock adapters and scheduled workers disabled by default for tests.
- [ ] Perform independent code review; fix actionable findings and run affected checks.
- [ ] Check whitespace, exact file structure and obsolete imports; record final verification.

## Progress and rulings

- Design approved by user; continue implementation without another approval checkpoint.
- Existing baseline: 33 fulfillment/payment/reconciliation tests passed before implementation.
- Ruling: use the supplied checkout because the approved design explicitly preserves its uncommitted work; a new checkout would omit that prerequisite code.
- Ruling: delegate supplier CRUD/mapping implementation and tests together; root owns adapter relocation, registry, documentation and verification. Serialize Maven runs to avoid shared target output races.
- Management TDD: 20 API tests initially produced 16 expected route failures and 4 passing authorization cases; after implementation, all 20 API and 7 service tests passed.
- Registry TDD: new tests first failed to compile because the registry and key contract were absent; after implementation, all 48 registry/mock/fulfillment/payment/reconciliation tests passed.
- Independent adapter review: spec compliant; no actionable issues. Final whole-module review is in progress.
- Full-suite attempt stopped at compilation after additional, externally-created supplier/entity and supplier/repository classes appeared at 18:25-18:26. These were absent from the starting inventory and both passing test runs. Reconciliation of concurrent edits is pending; do not treat the full suite as passed.
