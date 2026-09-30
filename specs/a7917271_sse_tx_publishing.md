# Plan: Fix SSE Hub Transactional Publishing, Namespace Scoping, Framing, Connection Ownership and Error Handling

## Overview & Background

Branch `integration/sse-tx-convergence` introduced transaction boundary hardening (external execution out of transactions, short `REQUIRES_NEW` transactions for claim/persist/CAS, reply transaction isolated from resumption). **We must strictly maintain and NOT regress these transaction boundaries.**

This task refactors and hardens the SSE invalidation mechanism in `factory-service`:
1. **Transaction-Bound SSE Notifications**: Ensure SSE events are emitted strictly AFTER transaction commit via Spring `TransactionSynchronizationManager` (or immediately if outside a transaction, or never if rolled back). Events payloads include `revision` (and `workflowId`, `namespaceId`) so clients can ignore stale REST re-reads.
2. **Immediate Initial Framing**: Emit an initial comment/framing frame upon `WorkflowSseHub.register(...)` so clients see the HTTP response body and stream opening immediately without waiting 30 seconds for the first heartbeat.
3. **Explicit Scope Contract & Connection Ownership**: Standardize namespace scoping and tenant isolation in `WorkflowSseController` and `WorkflowSseHub`. Prevent unpartitioned global broadcasts across tenants. Clean up connection ownership so each namespace key is strictly partitioned by scope/tenant context.
4. **Servlet Lifecycle & Disconnect Error Handling**: In `WorkflowSseHub`, properly handle client/proxy disconnects (`ClientAbortException`, `IOException` / Broken pipe, `ServletException`, `AsyncRequestTimeoutException`) without calling `completeWithError()` on dead emitters, logging spurious 500 errors, or causing double completion in Spring/Tomcat/Jetty. Ensure slow clients do not block core business execution (catch/remove/drop or async/non-blocking send).

---

## Targeted Files & Touchpoints

1. `factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseHub.kt`
   - Core SSE registry, emission, heartbeat, transaction synchronization helper, framing, and disconnect exception classification.
2. `factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseController.kt`
   - Endpoint contract validation, tenant scope & namespace resolution, parameter validation, initial registration.
3. `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
   - Invocation points of `sseHub.publish(...)` (replace direct calls or wrap inside `publishAfterCommit` or `WorkflowSseHub` internal post-commit hook).
4. `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`
   - Invocation points of `sseHub.publish(...)` (ensure session state changes publish after commit with workflowId, namespaceId, and revision if known).
5. `factory-service/src/test/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseHubTest.kt`
   - Unit tests covering initial framing, transaction after-commit publishing, rollback drop, slow client drop, disconnect exception handling.
6. `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowSseHttpTest.kt`
   - HTTP/Servlet level tests for initial framing, namespace scoping / multi-tenant separation, disconnects.
7. `factory-service/src/test/kotlin/io/whozoss/factory/workflow/TransactionBoundaryIntegrationTest.kt`
   - Integration tests ensuring transaction rollback prevents SSE emission, and commit triggers SSE emission.

---

## Step-by-Step Implementation Plan

### Step 1: Transaction-Bound SSE Invalidation Mechanics

**Requirements:**
- Notifications must ONLY be sent AFTER transaction commit (`TransactionSynchronizationManager`).
- If no transaction is active (e.g. non-transactional execution context or unit tests without transaction manager), publish immediately.
- If transaction rolls back, ZERO notifications are sent.
- Payload MUST include committed revision (when present/known) in addition to `workflowId` and `namespaceId`.

**Implementation Strategy in `WorkflowSseHub`:**
- Add `fun publish(namespaceId: String, payload: Any?, event: String = WorkflowProjectionEvents.UPDATED)` or `fun publishAfterCommit(...)`.
- Inside `publish(...)` or `publishAfterCommit(...)`:
  ```kotlin
  if (TransactionSynchronizationManager.isActualTransactionActive() &&
      TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
          override fun afterCommit() {
              sendToSubscribers(namespaceId, payload, event)
          }
      })
  } else {
      sendToSubscribers(namespaceId, payload, event)
  }
  ```
- Update callers in `WorkflowService` and `SessionRunService` to include `revision` whenever available in the payload map:
  e.g., `mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to revision)`
- In `SessionRunService`, when `persistProjection` succeeds and updates the instance to `next.revision`, pass `revision` into `sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to next.revision))`.

### Step 2: Immediate Initial Framing on Registration

**Requirements:**
- When client registers (`WorkflowSseHub.register`), immediately send an initial frame so the client receives response headers and initial data bytes immediately.

**Implementation Strategy in `WorkflowSseHub`:**
- Define initial comment frame constant: `const val OPEN_FRAME = ": open\n\n"` (or `: sse stream opened\n\n`).
- In `register(namespaceId, emitter)`:
  ```kotlin
  try {
      emitter.send(RawSseEvent(OPEN_FRAME))
  } catch (e: Exception) {
      if (isClientAbortException(e)) {
          removeClient(namespaceId, client)
          return emitter
      }
      removeClient(namespaceId, client)
  }
  ```
- Send this frame immediately synchronously during `register()` before returning `emitter`.

### Step 3: Explicit Scope Contract & Connection Ownership

**Requirements:**
- Disallow unpartitioned global broadcasts across tenants.
- Clarify subscription keys: Partition connections by tenant scope + namespace ID, or composite key `"${scope.organizationId}:${scope.workstreamId}:$namespaceId"`.
- If a client subscribes with a blank `namespaceId`, resolve authorized namespace context via `resolveWorkflowCaller` or partition under tenant-scoped key (e.g. `"${scope.organizationId}:${scope.workstreamId}:_all"` or scoped tenant key) so tenant A subscribing to `""` NEVER receives tenant B's scope-wide invalidations.
- Ensure explicit connection ownership and lifecycle management per scope/namespace key.

**Implementation Strategy:**
- In `WorkflowSseController`:
  - Validate parameters: `val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)`
  - Form an explicit internal registry key that incorporates tenant scope, e.g. `val hubKey = "${caller.scope.organizationId}:${caller.scope.workstreamId}:${caller.namespaceId}"` or pass `caller.scope` + `caller.namespaceId` to `hub.register(scopeKey, namespaceId)`.
  - When publishing in `WorkflowService` / `SessionRunService`, use `sseHub.publish(scope, namespaceId, payload)` or publish to both concrete namespace key and tenant `_all` key within the same tenant scope.
  - No event payload will cross tenant boundaries.

### Step 4: Robust Servlet Lifecycle & Disconnect Error Handling

**Requirements:**
- Distinguish expected client/proxy disconnections (`ClientAbortException`, `Broken pipe`, `EOFException`, `IOException` during send, `AsyncRequestTimeoutException`) from real server exceptions.
- Do NOT blindly call `completeWithError()` or duplicate `complete()` calls on dead/disconnected emitters.
- Ensure slow clients do not block core business execution (e.g., non-blocking/catch-and-drop/remove disconnected clients immediately without holding lock or stalling thread).

**Implementation Strategy in `WorkflowSseHub`:**
- Implement exception helper `fun isClientAbortException(ex: Throwable): Boolean`:
  - Inspect `ex` and `ex.cause` for:
    - Class name contains `ClientAbortException` (Tomcat) or `EofException` (Jetty)
    - Exception message contains `"Broken pipe"`, `"Connection reset"`, `"EOF"`, `"Stream closed"`, or `"AsyncRequestTimeoutException"`
    - `IOException` on write
- Safe removal & completion helper:
  - Maintain `@Volatile var closed: Boolean` on `Client`.
  - When exception occurs during `send()`:
    - Mark `client.closed = true`
    - Cancel heartbeat timer
    - Remove client from registry map
    - Do **NOT** call `emitter.completeWithError(ex)` or `emitter.complete()` on a client that failed with a client-abort / broken-pipe exception, as Servlet container already closed the underlying response output stream. Calling `completeWithError` on closed container response triggers secondary 500 errors / `IllegalStateException: Response already committed`.
- Graceful handling for slow consumers:
  - Non-blocking loop: iterate over subscribers copy (`namespaceClients.toList()`).
  - If a write blocks or throws an exception, immediately catch, close client record, remove from list, and continue loop without re-throwing to caller thread. Core business execution or transaction commit handler is never blocked or failed by SSE dispatch.

---

## Detailed Plan Verification & Test Specifications

1. **Transaction Sync Unit/Integration Tests**:
   - `testSseEmittedOnlyAfterTransactionCommit()`: Start a `@Transactional` boundary in test, call `workflowService.publishProjection` (or `WorkflowSseHub.publish`), assert emitter received ZERO frames before commit, and EXACTLY ONE frame after commit.
   - `testSseNotEmittedOnTransactionRollback()`: Start a `@Transactional` boundary, call `workflowService.publishProjection`, trigger rollback (throw exception or `TestTransaction.flagForRollback()`), assert emitter received ZERO frames.
   - `testSseEmittedImmediatelyOutsideTransaction()`: Call `publish` outside active transaction, assert frame emitted immediately.

2. **Immediate Framing Unit Test**:
   - Register capturing emitter, assert `OPEN_FRAME` (`: open\n\n`) is present in `emitter.frames` immediately after `register()`.

3. **Tenant Scope Partitioning Test**:
   - Register subscriber A for `TenantScope(org="orgA", workstream="ws1")`, namespace="ns1".
   - Register subscriber B for `TenantScope(org="orgB", workstream="ws1")`, namespace="ns1" (or blank).
   - Publish event in Tenant A scope.
   - Assert Subscriber A gets event, Subscriber B gets NO event.

4. **Servlet Disconnect / Slow Client Test**:
   - Simulate `ClientAbortException` / `IOException("Broken pipe")` inside `emitter.send()`.
   - Assert exception is caught silently, client removed from hub, no 500 logged, no `completeWithError` invoked.
   - Simulate a slow client (mock emitter that delays or throws on write), assert `publish()` completes within < 1ms for caller thread.

5. **Full Affected Test Suite**:
   - Run `pnpm nx test factory-service` to verify zero regressions across all workflow, session, and HTTP integration tests.

---

## Implementation Deliverables Checklist

- [ ] `WorkflowSseHub.kt`:
  - `TransactionSynchronizationManager` integration in `publish()`.
  - Initial open frame in `register()`.
  - Client abort / disconnect exception classification logic.
  - Non-blocking, error-isolated client emission & clean removal.
  - Multi-tenant / scope aware keying or scope parameter support.
- [ ] `WorkflowSseController.kt`:
  - Tenant scope + namespace authorization and registration contract.
  - Response header setup and initial registration.
- [ ] `WorkflowService.kt` & `SessionRunService.kt`:
  - Updated `publish` calls with explicit revision payload where missing.
  - Ensuring post-commit notification guarantees.
- [ ] `WorkflowSseHubTest.kt` & `WorkflowSseHttpTest.kt` & `TransactionBoundaryIntegrationTest.kt`:
  - Comprehensive unit and integration test coverage for all acceptance criteria.
