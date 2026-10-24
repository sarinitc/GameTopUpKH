# GameTopUp KH — Project-Wide AI Agent Instructions

## Mission
Build and maintain the **existing** GameTopUp KH Spring Boot backend for a Cambodian game-credit storefront. It is being developed with future legitimate commercial use in mind. The backend uses Java, Spring Boot, PostgreSQL, Spring Data JPA, Spring Security/JWT, Flyway, Docker Compose and ABA PayWay sandbox. The user has reported implementing game catalog, packages, authentication, orders, payments, fulfillment, refunds, reconciliation and is building supplier management. **Inspect the repository before assuming any implementation is correct or present.**

## Mandatory operating rules
1. **Read this file first**, then `ai-agents/README.md`, `ai-agents/standards/engineering.md`, `ai-agents/standards/security-and-money.md`, and the task-specific role(s).
2. **Inspect existing code, tests, schema and Flyway history** before proposing changes. Preserve package conventions; don't generate duplicate classes or rewrite a module merely to reorganize it.
3. Work in small, reviewable changes: (a) describe intent and affected files, (b) implement, (c) run relevant tests, (d) report results, remaining risks and next action. Never claim tests passed without evidence.
4. Backend-only unless expressly requested. No Next.js/Flutter or infrastructure rebuilds by default.
5. **Never edit an applied Flyway migration** or silently enable Hibernate `ddl-auto=update` in production. Propose a new versioned migration and mention backup/staging requirements.
6. **Never expose secrets**, print access/refresh tokens, embed merchant credentials in source, or echo private keys into logs/prompts. Treat any previously disclosed ABA private key as compromised: require rotation before sensitive use.
7. **Separate money, orders and delivery.** QR generation is not payment success. Do not mark PAID without verified provider confirmation, matching amount/currency/reference. Do not mark COMPLETED without supplier confirmation.
8. Provider requests and callbacks may repeat. Make payment, refund and top-up actions idempotent; prevent double fulfillment with durable state, unique references, claiming/locking and reconciliation. Timeout/unknown supplier outcomes must not be blindly retried.
9. Real diamond delivery requires a verified authorized supplier and approved API; use mock supplier in isolated development while no agreement exists. Never ask for players' game passwords.
10. Every admin mutation needs authorization and auditability; every customer-owned resource needs ownership checks. Do not make dev/mock routes available publicly.
11. Use correct monetary types (`BigDecimal`/PostgreSQL `NUMERIC`) and UTC timestamps. Avoid floating-point money.
12. Ask for missing essential interface contracts when truly blocking; otherwise identify assumptions and proceed incrementally.

## Route tasks to roles
- Planning/architecture: `roles/01-architect.md`
- Schema/Flyway: `roles/02-database.md`
- Java/APIs: `roles/03-backend.md`
- Authentication/security: `roles/04-security.md`
- PayWay/payments: `roles/05-payment.md`
- Delivery: `roles/06-fulfillment.md`
- Testing: `roles/07-qa.md`
- Docker/CI/deployment: `roles/08-devops.md`
- Supplier catalog and mappings: `roles/09-supplier-management.md`
- Orders: `roles/10-order-management.md`
- Game catalog: `roles/11-catalog-management.md`
- Refunds: `roles/12-refund-management.md`
- Payment reconciliation: `roles/13-reconciliation.md`
- Logging/health/on-call: `roles/14-observability.md`
- Coordination: `roles/00-coordinator.md`

## Definition of done
Behavior verified with relevant unit/integration tests; API errors and access permissions checked; migration reviewed (if any); logging redacts sensitive data; backward compatibility, failure paths, retries and rollback considered; exact changed files and test results summarized.
