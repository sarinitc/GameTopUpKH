# Actuator monitoring implementation plan

Goal: add health, application info, and operational metrics to the existing Spring Boot 4.1.1 backend.

Use Actuator auto-configuration rather than custom monitoring controllers. Public GET health and liveness/readiness probes return status only; ADMIN can inspect component details, application info, and metrics using the existing JWT security chain. Readiness includes the database, while liveness remains independent of it. Expose only health, info, and metrics; disable other endpoint access and cap operations at read-only. Info contains curated application metadata. Runtime metrics use a stable application tag, with no user or payment identifiers.

No database migration, payment/supplier calls, deployment infrastructure, or domain-state changes are needed. The referenced ai-agents standards and roles are missing from this checkout; AGENTS.md applies.

- [x] Add integration tests in `src/test/java/org/example/gametopupkh/monitoring/ActuatorApiTests.java` and observe missing-endpoint/access-policy failures. Cover anonymous and USER/SUPPORT/ADMIN access, detail hiding, invalid JWTs, database-down probes, disabled endpoints, and real HTTP/runtime metrics.
- [x] Add the managed Actuator starter in `pom.xml`; configure exposure, health, info, and metrics in `src/main/resources/application.yml`; add monitoring matchers in `src/main/java/org/example/gametopupkh/security/SecurityConfig.java`.
- [x] Document endpoint usage and operational limits in `README.md`. Run focused tests, then the full suite against an isolated H2 database with workers disabled. Review the diff and obtain an independent code review.

Verification: Maven `-Dtest=ActuatorApiTests test`, then Maven `test` with test profile/H2 overrides for the existing unconfigured context test. Use cached Maven if the repository wrapper fails. Keep all credentials and tokens out of command output.

## Verification results

- Initial focused run before implementation: 18 tests, 17 expected assertion failures, no errors (missing endpoints/access restrictions); malformed bearer behavior already passed.
- Final focused run: 22 tests, no failures/errors/skips. Includes real HTTP metrics and simulated database connectivity failure.
- Full suite with monitoring: 280 tests, 256 passed, 18 failures, 6 errors, no skips.
- Unchanged baseline `8e446a8`, exported with `git archive` to `target/monitoring-baseline` and run with identical test overrides: 258 tests, 234 passed, 18 failures, 6 errors, no skips. Comparing fully qualified failing case names found exactly the same 24 cases, with no additions or removals. These failures predate monitoring; the full suite is not green.
- `git diff --check` passed. Logs are in `target/actuator-red.log`, `target/actuator-green.log`, `target/monitoring-full-suite.log`, and `target/monitoring-baseline-suite.log` (generated artifacts, not source changes).
- Independent code review: no actionable defects. Successful role checks use Spring Security test authentication; malformed JWT behavior is exercised through the existing filter.
- The repository wrapper failed with a PowerShell null-array error. Tests ran using the already cached Maven 3.10.0 distribution instead; no wrapper changes were made.

Full-suite invocation (PowerShell; replace `mvn` with the cached Maven executable if necessary):

```powershell
mvn -B `
  '-Dspring.profiles.active=test' `
  '-Dspring.datasource.url=jdbc:h2:mem:monitoring-regression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1' `
  '-Dspring.datasource.driver-class-name=org.h2.Driver' `
  '-Dspring.datasource.username=sa' '-Dspring.datasource.password=' `
  '-Dspring.jpa.hibernate.ddl-auto=create-drop' '-Dspring.flyway.enabled=false' `
  '-Dspring.jpa.defer-datasource-initialization=true' '-Dspring.sql.init.mode=always' `
  '-Dspring.sql.init.data-locations=classpath:db/migration/V2__seed_default_roles.sql' `
  '-Dapp.jwt.secret=monitoring-regression-test-secret-at-least-thirty-two-bytes' `
  '-Dpayment.reconciliation.enabled=false' '-Dfulfillment.worker.enabled=false' test
```

The test-only JWT key above is a synthetic fixture. H2 schema generation and role seeding are command-line test overrides; production datasource and Flyway settings were not changed. PostgreSQL migration execution and deployed endpoints were not tested.

### Existing failing cases reproduced on baseline

The following names are relative to `org.example.gametopupkh`:

```text
auth.VerificationApiTests.requestEmailVerificationCodeIsAccepted
fulfillment.FulfillmentConcurrencyTests.concurrentInitialDeliveriesShareOneSupplierCall
fulfillment.FulfillmentConcurrencyTests.concurrentManualRetriesShareOneSupplierCall
fulfillment.FulfillmentServiceTests.nullResultRemainsUnknownAndCannotBeResubmitted
fulfillment.FulfillmentServiceTests.supplierExceptionRemainsUnknownAndCannotBeResubmitted
fulfillment.FulfillmentServiceTests.unexpectedExceptionRemainsUnknownAndCannotBeResubmitted
fulfillment.SupplierSelectionFulfillmentTests.failedResultCommitLeavesDurableClaimAndNeverResubmits
fulfillment.SupplierSelectionFulfillmentTests.inactiveMappingCannotDeliver
fulfillment.SupplierSelectionFulfillmentTests.inactiveSupplierCannotDeliver(SupplierStatus)[1]
fulfillment.SupplierSelectionFulfillmentTests.inactiveSupplierCannotDeliver(SupplierStatus)[2]
fulfillment.SupplierSelectionFulfillmentTests.missingMappingRequiresReviewAndExplicitRetryCanRecover
fulfillment.SupplierSelectionFulfillmentTests.missingSuccessReferenceIsUnknownAndNeverResubmitted
fulfillment.SupplierSelectionFulfillmentTests.retryKeepsOriginalSupplierAndSkuAfterCatalogChanges
fulfillment.SupplierSelectionFulfillmentTests.selectedSupplierBecomingInactiveDoesNotRerouteRetry
payment.PaymentApiTests.mockSuccessEndpointCompletesThePaymentAndOrder
payment.PaymentApiTests.thePublicCallbackCompletesThePaymentAndOrder
payment.PaymentFulfillmentTests.duplicateAndNewCompletionCallbacksDoNotRepeatUnknownDelivery
payment.PaymentServiceTests.latePendingCheckCannotDowngradeAConcurrentCompletion
payment.PaymentServiceTests.mockConfirmationDoesNotDependOnEphemeralGatewayState
payment.PaymentServiceTests.mockReconciliationDoesNotDependOnEphemeralGatewayState
payment.PaymentServiceTests.verifiedCompletionMarksOrderPaidOnceAndDeduplicatesCallbacks
payment.reconciliation.PaymentReconciliationServiceTests.completedProviderStatusUpdatesPaymentOrderAndStartsFulfillmentOnce
payment.reconciliation.PaymentReconciliationServiceTests.needsReviewPaymentCanBeResolvedByAConfirmedProviderStatus
payment.reconciliation.PaymentReconciliationServiceTests.stalledInitializingPaymentIsResolvedByTheProviderStatus
```
