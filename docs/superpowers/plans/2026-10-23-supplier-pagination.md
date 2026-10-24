# Supplier Pagination Implementation Plan

> **For agentic workers:** Follow the test-first steps below; use superpowers:executing-plans for implementation and superpowers:verification-before-completion for evidence.

**Goal:** Add the approved paginated supplier-list response.

**Architecture:** Controller supplies query defaults; service validates bounds and
constructs a stable PageRequest; repository limits and counts at the database.
A SupplierPageResponse record preserves a fixed public JSON structure.

**Tech Stack:** Existing Java 17, Spring Boot, Spring Data JPA, MockMvc and H2.

**Spec:** `docs/superpowers/specs/2026-10-23-supplier-pagination-design.md`

## Global Constraints

Keep `org.example.gametopupkh.supplier`; ADMIN access; page 0/size 20 defaults;
size 1..100; existing status filter; no unrelated changes, secrets or migrations.
Work against the existing uncommitted supplier implementation in this checkout.

## Review Focus

- Default limit must actually restrict rows and still report total count.
- Status filtering must apply before pagination and to the count query.
- Identical creation timestamps must not cause unstable page boundaries.
- Invalid bounds and offset overflow must return API errors, not server errors.
- Empty/out-of-range pages must retain truthful metadata and role protection.

### Task 1: Supplier pagination and documentation

**Files:** Modify SupplierController.java, SupplierService.java,
SupplierServiceImpl.java and SupplierRepository.java in the flat supplier package.
Create SupplierPageResponse.java there. Update SupplierManagementApiTests.java;
create SupplierPaginationApiTests.java in the same test package. Update only the
supplier-management section of README.md.

**Interfaces:** `SupplierPageResponse findAll(SupplierStatus status, int page,
int size)`; repository `Page<Supplier> findAllByStatus(SupplierStatus status,
Pageable pageable)` plus inherited `findAll(Pageable)`; response fields are
`List<SupplierResponse> content`, `int page`, `int size`, `long totalElements`,
`int totalPages`.

- [ ] Write real MockMvc/H2 pagination tests and update existing supplier-list
      assertions to use `data.content`; keep product-list assertions unchanged.
- [ ] Run `mvn -B -o -Dtest=SupplierPaginationApiTests,SupplierManagementApiTests test`.
      Expected: failures for old array responses and ignored pagination inputs.
- [ ] Implement query defaults, validation, database paging, stable sorting and DTO.
- [ ] Document response compatibility, examples, defaults and error behavior.
- [ ] Run the targeted tests. Expected: all pass.
- [ ] Have an independent reviewer check scoped changes and boundary cases.
- [ ] Run `mvn -B -o test`. Expected: the full suite passes with no failures/errors.
- [ ] Verify change scope and report results; leave work uncommitted.

## Execution notes

The full 196-test suite passed immediately before this feature. The Windows
wrapper fails in this tool environment; use cached Maven 3.10.0 with the explicit
local repository path and the already approved cache-lock access.
Uncommitted supplier files are the baseline, so review against saved pre-change
copies rather than treating all untracked files as newly authored work.
