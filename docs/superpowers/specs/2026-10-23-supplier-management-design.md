# Supplier management design

## Intent and scope

Add administrative supplier management after the existing ABA verification and
reconciliation work. Follow the user's flat `supplier/` structure and the five
specified `/api/v1/admin/supplier-products` endpoints. Reuse the existing
`suppliers` and `supplier_products` tables. All management operations require
ADMIN through the existing security configuration.

## Approaches

The user supplied a flat supplier module with nested integration adapters. This
matches the existing order/payment/fulfillment modules and is the proposed
approach. Keeping the currently scaffolded entity/repository subpackages would
also work, but would not match the requested structure. Real supplier routing
would additionally require a provider contract, mapping-selection rules and
persisted delivery identity; it is a separate extension.

## Structure

Create the requested Supplier and SupplierProduct entities, repositories,
service interfaces and implementations, controllers, and request/response DTOs
directly under `org.example.gametopupkh.supplier`. Move SupplierStatus there,
preserving ACTIVE, INACTIVE and SUSPENDED.

Move the existing SupplierClient and MockSupplierClient into
`supplier.integration`, together with SupplierTopupRequest and
SupplierTopupResponse. Update application and test imports rather than retaining
duplicate adapter classes. Add SupplierClientRegistry to resolve the configured
adapter by a stable key (`mock`), reject unknown or duplicate keys, and preserve
the default mock behavior. Editable supplier names are not adapter keys.

## Supplier API (proposed companion operations)

Expose POST/GET `/api/v1/admin/suppliers`, GET/PUT
`/api/v1/admin/suppliers/{id}`, and PATCH
`/api/v1/admin/suppliers/{id}/status`. Create accepts a required name (trimmed,
1–150 characters) and optional status, defaulting to INACTIVE as in the schema.
PUT updates the name; PATCH requires a status. List optionally filters by status
and orders newest first. No delete endpoint is included.

## Supplier-product API (user requested)

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/admin/supplier-products` | Create a supplier-to-package mapping; return 201 |
| GET | `/api/v1/admin/supplier-products` | List mappings, optionally filter by supplierId, packageId and active |
| GET | `/api/v1/admin/supplier-products/{id}` | Return a mapping, including disabled mappings |
| PUT | `/api/v1/admin/supplier-products/{id}` | Update externalSku, costPrice and optional currency |
| PATCH | `/api/v1/admin/supplier-products/{id}/status` | Enable or disable using a required boolean active |

Create requires supplierId, packageId, externalSku and costPrice; currency
defaults to USD and active defaults to true. SKU is trimmed, nonblank, at most
150 characters, and preserves case. Cost is nonnegative with at most ten
integer digits and two fractional digits. Currency is three uppercase letters.
PUT retains the existing currency if it is omitted. Supplier and package IDs
remain unchanged after creation; conflicting IDs in an update are rejected.

Creating or enabling a mapping requires an ACTIVE supplier and an existing,
active catalog package. Disabling always remains available. Supplier status
changes do not erase or automatically rewrite existing mapping records.
Display/administration of existing mappings remains available for inactive
suppliers. A mapping's active flag is distinct from delivery eligibility.

Enforce the existing unique (supplier_id, external_sku) constraint; duplicate
creates/updates return 409, including database-detected races. Do not add a
uniqueness rule for (supplier_id, package_id): the schema permits different
external SKUs for one package. Missing resources return 404, malformed or
invalid payloads return 400, and inactive-parent conflicts return 409. Use the
existing ApiResponse envelope and explicit response records.

## Integration boundary

Fulfillment continues to use the configured mock adapter through the registry.
It does not select supplier-product records for delivery in this phase. Keep
the current payment callbacks, reconciliation, order transitions, stable mock
references and retry behavior. The registry prepares adapter selection without
implying that arbitrary database suppliers have a working external integration.
No real provider requests, credential storage, automatic failover, pricing
selection, balance management or database migration are required.

## Builder decisions and verification

The companion supplier endpoints, defaults, filters, immutable mapping parent
IDs and registry behavior above are proposed implementation choices; the user
explicitly supplied the class layout and product endpoint table.

Follow existing JPA UUID/timestamp conventions and Spring transactional
services. Tests should cover management lifecycles, authentication and roles,
field limits and precision, unknown parents/IDs, duplicate SKU handling,
inactive-parent rules and status changes. Registry tests cover default,
unknown and duplicate adapters. Run existing fulfillment/payment tests after
the package move, then the full suite using isolated H2 databases and mock
adapters. Preserve unrelated uncommitted changes and leave work uncommitted.
