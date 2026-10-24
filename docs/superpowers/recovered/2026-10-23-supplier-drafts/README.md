# Preserved concurrent supplier drafts

These source files appeared during implementation at 18:25–18:26 on
2026-10-23, after the approved flat supplier module and its targeted tests were
already complete. They duplicate the new supplier entities/repository and the
old-package Supplier references an enum that moved into the flat package,
preventing compilation. They are preserved byte-for-byte here rather than
discarded. The `.txt` suffix keeps the alternate sources outside the Java build.

Original paths relative to `src/main/java/org/example/gametopupkh/supplier/`:

- `entity-Supplier.java.txt`: `entity/Supplier.java`
- `entity-SupplierProduct.java.txt`: `entity/SupplierProduct.java`
- `entity-package-info.java.txt`: `entity/package-info.java`
- `repository-SupplierRepository.java.txt`: `repository/SupplierRepository.java`
- `repository-SupplierProductRepository.java.txt`: `repository/SupplierProductRepository.java`
- `repository-package-info.java.txt`: `repository/package-info.java`
- `dto-request-SupplierRequest.java.txt`: `dto/request/SupplierRequest.java`

`repository/SupplierProductRepository.java` still imported the missing
`supplier.entity.SupplierProduct` draft and blocked compilation until it was
moved here alongside the other stale drafts.

The active implementation uses the flat layout approved in
`docs/superpowers/specs/2026-10-23-supplier-management-design.md`.
