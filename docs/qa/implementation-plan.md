# PostgreSQL integration QA plan

Inspect the complete backend by domain, preserving the existing Actuator changes. Existing tests use JUnit Jupiter, Spring Boot Test, MockMvc and Mockito; none use Testcontainers. Retain fast H2/unit coverage and add real PostgreSQL 17 coverage for Flyway constraints, committed transactions and concurrency. Tests use isolated disposable data, mock ABA transport and the mock supplier; no live merchant or supplier request is permitted.

1. Inventory existing tests, migrations and production code across authentication, catalog/orders, payment/reconciliation/refunds, supplier/fulfillment, security, auditing, monitoring and shared configuration.
2. Add test-scoped Testcontainers dependencies, safe test defaults, a singleton PostgreSQL base, and per-test cleanup. Execute all real Flyway migrations and validate mappings. Do not skip tests silently if Docker is unavailable.
3. Add PostgreSQL suites for authentication/security/audit, catalog/order validation and ownership, ABA provider verification/reconciliation/refunds and duplicate events, supplier selection/fulfillment and concurrent claims. Extend existing safe mocks and repair invalid fixtures without weakening expectations.
4. Run new tests before production fixes. Restrict changes to reproduced defects: validation groups, payment reference verification, and durable fulfillment claims/unknown outcomes as evidence warrants. No applied migration edits.
5. Run the complete Maven suite, review fixes independently, and report counts, per-domain coverage, exact changes and remaining risks. Preserve genuine failing tests in the report if resolving them would require a separate feature or provider contract.

Commands use cached Maven 3.10.0 because the repository wrapper currently fails in PowerShell. Standard project entrypoints remain `mvn test` / `mvn -Dtest=*PostgresTests test`; test containers require Docker. Run serially because a disposable database is cleaned between cases.
