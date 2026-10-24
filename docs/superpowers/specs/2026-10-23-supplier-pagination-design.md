# Supplier list pagination

The user approved the recommended paginated object response for the existing
ADMIN-only `GET /api/v1/admin/suppliers` endpoint. Keep the current flat
`org.example.gametopupkh.supplier` package and existing `ApiResponse` envelope.

## Contract

- `page`: zero-based, default 0, nonnegative.
- `size`: default 20, from 1 through 100.
- Optional `status` filtering retains ACTIVE, INACTIVE and SUSPENDED.
- `data` contains `content`, `page`, `size`, `totalElements`, `totalPages`.
- Apply filtering and pagination in the database; count only matching suppliers.
- Keep newest-first ordering; use descending UUID as a stable tie-breaker.
- Empty results and valid pages beyond the end return 200 with empty content.
- Invalid numeric bounds, including offsets above JPA's integer offset range,
  return 400/INVALID_PAGINATION. Non-integer parameters retain HTTP_400 errors.
- Existing authentication/authorization and all mutation/product APIs stay intact.

## Approaches and compatibility

The user selected a paginated object over preserving the array with HTTP headers.
Clients must read `data.content` instead of `data`. Use a supplier-specific
`SupplierPageResponse` record to make this API shape explicit without changing
`ApiResponse` or exposing Spring's internal Page serialization.

## Scope and verification

No base-package rename, dependency change, schema migration or supplier mutation
audit-history implementation. The existing audit-history gap remains separate.
Add API integration tests for actual page contents, totals, filtering, ties,
empty/out-of-range results and invalid inputs; retain role tests. Run the targeted
supplier tests and full suite using the already cached Maven 3.10.0 distribution.
Preserve all unrelated uncommitted work and leave changes uncommitted.
