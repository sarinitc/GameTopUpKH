# Supplier Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Select one eligible supplier-product mapping deterministically without changing delivery.

**Architecture:** A read-only internal service validates package and currency, then uses a bounded JPA query. Existing entities and schema remain intact.

**Tech Stack:** Java, Spring Boot, Spring Data JPA, JUnit, H2.

**Spec:** `docs/superpowers/specs/2026-10-24-supplier-selection-design.md`

## Global Constraints

- Lowest cost is an implementation assumption, not user approval of policy.
- No currency conversion, payment or fulfillment changes, outbound supplier calls, or database migration.
- No locks, reservation, or guarantee that eligibility remains unchanged after selection returns.
- Preserve the dirty checkout, including uncommitted supplier prerequisites; leave changes uncommitted.

## Review Focus

- Active mappings under suspended suppliers must be excluded using current supplier status.
- Other currencies must never compete by their numeric cost.
- Multiple SKUs for one supplier/package remain valid independent candidates.
- Equal costs require creation-time and UUID tie breakers.
- The query must bound results in the database and check package activity.

### Task 1: Implement and verify selection

**Files:**
- Create `src/main/java/org/example/gametopupkh/supplier/SupplierSelectionService.java`.
- Create `src/main/java/org/example/gametopupkh/supplier/SupplierSelectionServiceImpl.java`.
- Modify `src/main/java/org/example/gametopupkh/supplier/SupplierProductRepository.java`.
- Create `src/test/java/org/example/gametopupkh/supplier/SupplierSelectionServiceTests.java`.

**Interfaces:**
- Produce `SupplierProduct selectForPackage(UUID packageId, String currency)`.
- Produce `List<SupplierProduct> findEligibleForSelection(UUID packageId, String currency, SupplierStatus status, Pageable pageable)`.
- Consume existing `GamePackageRepository.findById(UUID)` and `ApiException`.

- [ ] Write integration tests for lowest cost, multiple SKUs, mapping/supplier/package/currency exclusions, ties by createdAt then UUID, and no candidate.
- [ ] Add malformed-input tests asserting 400 `INVALID_SUPPLIER_SELECTION`, missing-package 404 `PACKAGE_NOT_FOUND`, inactive-package 409 `PACKAGE_INACTIVE`, and no-candidate 409 `NO_SUPPLIER_AVAILABLE`.
- [ ] Run `mvn -Dtest=SupplierSelectionServiceTests test`; confirm failure from missing implementation before coding, once Maven can resolve dependencies.
- [ ] Implement the interface and read-only service; reject null packageId or currency failing `^[A-Z]{3}$`, validate the package, and query ACTIVE suppliers with page zero/size one.
- [ ] Add the repository join query checking mapping active, package active, exact package/currency and supplier status, ordered by costPrice, createdAt and id ascending.
- [ ] Run `mvn -Dtest=SupplierSelectionServiceTests test`; require zero failures/errors and inspect bounded-query behavior.
- [ ] Run `mvn -Dtest=SupplierSelectionServiceTests,SupplierProductServiceTests,SupplierManagementApiTests,SupplierPaginationApiTests test`; require zero failures/errors.
- [ ] Obtain independent review of eligibility, ordering, validation, query bound and preserved fulfillment behavior; address concrete findings and rerun affected tests.
- [ ] Run `git diff --check`; report changed files, actual test results, assumptions and concurrency/PostgreSQL limits. Do not commit.

## Progress and environment

- Repository/schema/test inspection completed; no existing selection policy was found.
- Maven wrapper currently fails with a null `Target[0]` error.
- Direct cached Maven was attempted, but dependency resolution was blocked in the sandbox; an escalated test run is pending.
- No test success is claimed by this plan. Use the cached Maven executable in place of `mvn` if needed; retain the same goals and test filters.
