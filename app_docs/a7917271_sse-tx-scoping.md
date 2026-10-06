# Transaction-safe, tenant-scoped workflow SSE

## What changed

`factory-service` workflow SSE notifications are now transaction-aware and tenant-scoped:

- `WorkflowSseHub.publish(...)` registers a Spring `TransactionSynchronization` when an actual transaction and synchronization are active, dispatching only from `afterCommit`. Rollbacks therefore emit no event; calls outside a transaction still dispatch immediately.
- Workflow event payloads carry `workflowId`, `namespaceId`, and the committed `revision` wherever the revision is known. `SessionRunService.persistProjection` now returns the resulting revision so its notification can include it.
- Registration writes an immediate `: open\n\n` frame, in addition to the existing periodic `: heartbeat\n\n` frames.
- Production registrations and publishes use `TenantScope` plus an optional namespace. Concrete subscriptions are keyed by organization/workstream/namespace, while blank namespaces use a tenant-only key. A concrete namespace publish reaches that namespace and the same tenant's whole-tenant subscribers; a blank publish reaches only whole-tenant subscribers. The blank key is never the shared `""` bucket.
- `WorkflowSseController` resolves identity and scope from the verified trust context, permits a blank namespace as a tenant-wide filter, and fails closed before opening SSE when the trust context is missing or unauthorized.
- Emitter writes are isolated per client. Broken pipes, EOFs, Tomcat/Jetty/Spring disconnect exception names, and related messages are classified as expected disconnects; failed clients are removed without `completeWithError` or a second completion. Other send failures are logged and also dropped, so an SSE failure is not propagated into business execution. Shutdown and timeout completion are best-effort and guarded.

The raw string registration/publish overloads remain for legacy/unit-test use; scope-aware production paths use the composite subscription key.

## Files carrying the change

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseHub.kt`: transaction synchronization, subscription-key construction, open/heartbeat framing, dispatch, emitter lifecycle, and disconnect classification.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseController.kt`: documented and enforced tenant-scope subscription contract and scoped registration.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`: all workflow projection/lifecycle SSE calls now pass the verified scope and revision-bearing payloads.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`: projection persistence returns its revision and the session notification passes it with the scope.

## Verification coverage

`WorkflowSseHubTest.kt` covers immediate opening, heartbeat framing, commit-only dispatch, rollback suppression, immediate non-transactional dispatch, tenant isolation, tenant-wide fanout rules, disconnect classification, failed-emitter removal, and continued delivery to healthy clients. `WorkflowSseHttpTest.kt` verifies scoped concrete and namespace-less HTTP subscriptions and publishing. `SessionDefinitionImportIntegrationTest.kt` was updated to register its emitter through the scoped API.

The change also adds `specs/a7917271_sse_tx_publishing.md`, which records the SSE transaction/scoping/framing/error-handling plan and verification checklist.

To verify the affected Kotlin tests, run the repository's factory-service test target (for example `pnpm nx test factory-service`).
