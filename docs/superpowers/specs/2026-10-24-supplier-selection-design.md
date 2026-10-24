# Supplier selection design

## Intent and current state

Add internal, read-only selection of a supplier-product mapping for a package and currency.
Existing supplier management owns mappings; fulfillment still uses the configured mock adapter.
The existing schema supports multiple SKUs per supplier/package, cost, currency and active state.

## Approaches and assumption

Lowest eligible cost requires no schema change and provides a useful deterministic default.
Explicit supplier priority would require a new policy and schema; manual choice would leave callers to duplicate eligibility rules.
Proceed with lowest cost as the implementation assumption because the optional policy question has not received an answer.
This assumption is not user approval of a selection policy.

## Contract

`SupplierSelectionService.selectForPackage(UUID packageId, String currency)` returns `SupplierProduct`.
`SupplierSelectionServiceImpl` is an internal Spring `@Service` with a read-only transaction.
Reject null packageId or currency not matching `^[A-Z]{3}$` with 400 `INVALID_SUPPLIER_SELECTION`.
Do not trim or normalize currency; callers provide the exact currency to compare.
Require an existing package, otherwise 404 `PACKAGE_NOT_FOUND`; require active package, otherwise 409 `PACKAGE_INACTIVE`.

Select only active mappings for the requested package and exact currency whose supplier is currently `ACTIVE`.
The query also checks that the package remains active at query time.
Sort by `costPrice ASC, createdAt ASC, id ASC`; request database page zero with size one.
If no eligible mapping exists, return 409 `NO_SUPPLIER_AVAILABLE`.
Use the existing `ApiException` conventions.

Add `SupplierProductRepository.findEligibleForSelection(UUID packageId, String currency, SupplierStatus status, Pageable pageable)` returning `List<SupplierProduct>`.
Join existing entities using their scalar supplier/package IDs; do not introduce new entity relationships.

## Boundaries and risks

No currency conversion, payment or fulfillment changes, outbound supplier calls, or database migration.
No locks, reservation, or guarantee that eligibility remains unchanged after selection returns.
Delivery must separately establish its supplier contract and durable identity before using a selected mapping.
Preserve the dirty checkout, including uncommitted supplier prerequisites; leave changes uncommitted.

## Verification

One integration test class exercises the real H2 JPA repository and service.
Cover lowest cost, multiple SKUs, disabled mapping, INACTIVE/SUSPENDED supplier, other package/currency, and inactive package exclusion.
Cover cost ties resolved by creation time then UUID, no candidate, missing/inactive package, and malformed arguments.
Use isolated test storage and mock adapters; no live requests.
Run focused supplier regressions and review the bounded query and failure paths independently.
H2 tests do not substitute for PostgreSQL production validation; this change introduces no migration.
