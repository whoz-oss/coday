# Implementation Plan - Factory Engine First Phase 1 (Sub-tasks 1, 2, 3)

## Overview

This plan details the implementation for Phase 1 of the factory-engine-first initiative in `factory-service`.
It covers three sub-tasks:
1. **Dynamic expectedRevision handling in SessionRunService**: Reconcile transition expectedRevision handling by replacing hardcoded `expectedRevision = 1` in `SessionRunService.transition(...)` with the dynamic revision from `activeInstance(scope, namespaceId, workflowId).revision` (or passing the snapshot/instance revision explicitly), eliminating 409 conflict issues.
2. **Capability Token & Result Binding REST Endpoint**:
   - Issue capability tokens at agent step start using `AgentStepResultService.issue(...)` in `CapabilityExecutionService.resolveAgent(...)`.
   - Implement `FactoryStepResultBindingController` (or host it cleanly as a REST controller under `web/`) providing endpoint support for binding results / checking capabilities as required.
   - Transmit `attemptId` and `capabilityToken` in `AgentOsAgentTurnCapability` down to `HttpAgentOsProxyClient` and into AgentOS case creation (`createCase` / case metadata or brief).
3. **Outbox Drain Worker & Sequence Continuation**:
   - Add a `@Scheduled` task in `factory-service` calling `OutboxDrainService.drainPending`.
   - On processing `result_submitted` outbox events, trigger DAG advancement / sequencer continuation via `SessionRunService.runSession` / `continueSession`.
   - Decouple synchronous HTTP transactions from agent turn execution.

---

## Technical Analysis & Target Files

### 1. Sub-task 1: Reconcile `SessionRunService` transition `expectedRevision`

#### Problem
In `SessionRunService.kt`:
```kotlin
private fun transition(
    scope: TenantScope,
    namespaceId: String,
    workflowId: String,
    step: WorkflowStepDefinition,
    status: String,
) {
    repository.appendTransition(
        ...,
        request = WorkflowTransitionRequest(
            ...,
            expectedRevision = 1, // Hardcoded!
            ...
        ),
        ...
    )
}
```
When `appendTransition` is called across multiple steps or turns on the same session, `expectedRevision = 1` does not reflect the current instance revision (which increments on every step state update and `updateInstance`). While `JdbcWorkflowRepository.appendTransition` inserts into `workflow_transitions` without checking `expectedRevision` in the SQL `INSERT`, `WorkflowTransitionRequest.expectedRevision` is stored inside transition records or validated by transition policies, causing 409 / stale revision errors when transitions are checked or processed against snapshots.

#### Solution
- Modify `SessionRunService.transition(...)` to take `expectedRevision: Int` as a parameter or load/pass `instance.revision` (e.g. from `activeInstance(scope, namespaceId, workflowId).revision` or from the current `instance` parameter already retrieved during `evaluateStep` / `evaluateSession`).
- Check all internal call sites of `transition` in `SessionRunService.kt`:
  - `evaluateStep`
  - `resolveWaitingHuman`
  - Any error or retry handlers
- Ensure `expectedRevision` matches the active instance's revision at transition time.

---

### 2. Sub-task 2: Result Capability Generation, Binding REST Endpoint & AgentOS Proxy Parameters

#### Requirements
1. **Capability Generation at Step Start**:
   - In `CapabilityExecutionService.resolveAgent(...)`: before or when inserting `AgentStepAttemptRecord` (`running`), call `agentStepResultService.issue(scope, AgentStepCapabilityIdentity(...))` to issue a submission capability for the attempt.
   - Obtain `IssuedCapability(token, expiresAt, ...)` containing the clear capability token (`capabilityToken`).
2. **REST Endpoint `FactoryStepResultBindingController`**:
   - Host `FactoryStepResultBindingController` under `web/` (or update/expose `AgentStepResultController` / dedicated controller endpoint `POST /api/factory/step-result-bindings` or similar binding endpoint as specified by the contract/OpenAPI spec).
   - Ensure it acts as a true Spring `@RestController` registered in the application context.
   - Endpoint maps request bindings, validates capability tokens via `AgentStepResultService`, and exposes capability / result status endpoints if required.
3. **Transmit `attemptId` and `capabilityToken` to AgentOS**:
   - Update `AgentTurnRequest` in `AgentTurnCapability.kt` to include `attemptId: String?` and `capabilityToken: String?`.
   - Update `CapabilityResolver` and `CapabilityExecutionService` to supply `attemptId` and `capabilityToken` when invoking `agentTurnCapability.executeAgentTurn(...)`.
   - Update `AgentOsAgentTurnCapability` to forward `attemptId` and `capabilityToken` in calls to `AgentOsProxyClient` / `HttpAgentOsProxyClient.executeAgentTurn(...)`.
   - Update `HttpAgentOsProxyClient.executeAgentTurn(...)` and `createCase(...)` / `postMessage(...)` to transmit `attemptId` and `capabilityToken` in headers or body to AgentOS case creation (`POST /api/cases` body `{ "namespaceId": ..., "title": ..., "attemptId": ..., "capabilityToken": ... }` and/or headers `X-Attempt-Id`, `X-Capability-Token`).

---

### 3. Sub-task 3: Outbox Drain Scheduled Task & Sequencer Continuation

#### Requirements
1. **Scheduled Outbox Drain Worker**:
   - Enable Spring scheduling in `factory-service` by adding `@EnableScheduling` on a configuration class (e.g., `OutboxSchedulingConfiguration.kt` or `FactoryServiceApplication.kt`).
   - Create `OutboxDrainWorker.kt` component with a `@Scheduled(fixedDelayString = "${factory.outbox.drain-interval-ms:5000}")` method.
   - Worker queries tenants/organizations or default scope and calls `outboxDrainService.drainPending(organizationId, limit = 50) { event -> handleOutboxEvent(event) }`.
2. **Sequencer Continuation on `result_submitted`**:
   - In `handleOutboxEvent(event: OutboxEvent)`:
     - Check if `event.eventType == "result_submitted"`.
     - Parse payload JSON to extract `namespaceId`, `workflowId`, `attemptId`, `resultId`, `status`.
     - Look up session/instance associated with `attemptId` or `workflowId`.
     - Invoke `sessionRunService.runSession(scope, namespaceId, workflowId, repoRoot)` (or `continueSession`) to advance the DAG sequencer now that the agent step result has been submitted.
3. **Decouple Synchronous HTTP Transactions from Turn Execution**:
   - Ensure agent turn execution or result processing does not block inside synchronous HTTP request/response loops. When an agent submits a result asynchronously via REST, the outbox event handler triggers sequencer continuation in the background.

---

## Step-by-Step Implementation Steps

### Step 1: SessionRunService Revision Reconciliation
- File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`
- Action:
  1. Update `transition` helper method signature:
     ```kotlin
     private fun transition(
         scope: TenantScope,
         namespaceId: String,
         workflowId: String,
         step: WorkflowStepDefinition,
         status: String,
         expectedRevision: Int,
     )
     ```
  2. Use `expectedRevision` when constructing `WorkflowTransitionRequest`:
     ```kotlin
     expectedRevision = expectedRevision,
     ```
  3. In `evaluateStep(...)`, pass `instance.revision` to `transition(...)`.
  4. In `resolveWaitingHuman(...)`, pass `activeInstance(scope, namespaceId, workflowId).revision` (or current snapshot revision) to `transition(...)`.

### Step 2: Capability Token Generation & AgentOS Parameter Propagation
- File: `factory-service/src/main/kotlin/io/whozoss/factory/capability/AgentTurnCapability.kt`
  - Add `attemptId: String? = null` and `capabilityToken: String? = null` to `AgentTurnRequest`.
- File: `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
  - Inject `AgentStepResultService` into `CapabilityExecutionService`.
  - In `resolveAgent(...)`:
    1. Create attempt ID: `val attemptId = UUID.randomUUID().toString()`.
    2. Insert running attempt in `attemptRepository`.
    3. Call `agentStepResultService.issue(scope, AgentStepCapabilityIdentity(attemptId = attemptId, caseId = null, agentName = agentId))` to mint `IssuedCapability`.
    4. Pass `attemptId` and `issuedCapability.token` in `AgentTurnRequest` to `resolver.resolve(...)`.
- File: `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityResolver.kt`
  - Forward `attemptId` and `capabilityToken` in `AgentTurnRequest`.
- File: `factory-service/src/main/kotlin/io/whozoss/factory/capability/AgentOsAgentTurnCapability.kt`
  - Pass `attemptId` and `capabilityToken` from `request` to `client.executeAgentTurn(...)`.
- File: `factory-service/src/main/kotlin/io/whozoss/factory/proxy/AgentOsProxyClient.kt` & `HttpAgentOsProxyClient.kt`
  - Update `executeAgentTurn` interface and implementation to accept `attemptId: String? = null` and `capabilityToken: String? = null`.
  - In `createCase(...)` (or `executeAgentTurn`), include `attemptId` and `capabilityToken` in payload / headers sent to AgentOS `POST /api/cases`.

### Step 3: REST Controller `FactoryStepResultBindingController`
- File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/web/FactoryStepResultBindingController.kt`
  - Create `@RestController` at `/api/factory/step-result-bindings` (or corresponding binding endpoint).
  - Inject `AgentStepResultService` and `TenantScopeProvider`.
  - Expose binding management methods (e.g., binding verification, token resolution, step result attachment).

### Step 4: Outbox Scheduler & DAG Continuation Trigger
- File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/OutboxDrainWorker.kt`
  - Create `@Component` class using `@Scheduled(fixedDelay = 5000)` or configurable interval.
  - Inject `OutboxDrainService`, `SessionRunService`, `AgentStepAttemptRepository`, `WorkstreamRepository` / tenant provider.
  - In `drain()`:
    - Iterate active tenants/organizations.
    - Call `outboxDrainService.drainPending(orgId)` with event handler:
      - If `event.eventType == "result_submitted"`:
        - Parse payload for `attemptId`, `resultId`.
        - Retrieve attempt record via `attemptRepository` to find `(namespaceId, workflowId)`.
        - Trigger DAG continuation: `sessionRunService.runSession(scope, namespaceId, workflowId, repoRoot)`.
- File: `factory-service/src/main/kotlin/io/whozoss/factory/config/OutboxSchedulingConfiguration.kt`
  - Add `@Configuration` and `@EnableScheduling`.

### Step 5: Testing & Verification
- Run Gradle / Nx tests to verify all unit and integration tests pass:
  - `nx test factory-service --tests io.whozoss.factory.workflow.SessionSequencerIntegrationTest`
  - `nx test factory-service --tests io.whozoss.factory.agentattempt.AgentStepResultServiceIntegrationTest`
  - `nx test factory-service --tests io.whozoss.factory.agentattempt.OutboxDrainServiceIntegrationTest`
  - `nx test factory-service`

---

## Verification Plan

### Manual / Automated Tests
1. **SessionRunService Integration Test**: Verify session steps transition cleanly without revision conflict errors.
2. **Capability Issue & Turn Test**: Verify `CapabilityExecutionService.resolveAgent` issues a capability token, sets attempt ID, and passes it through `AgentOsAgentTurnCapability` to `HttpAgentOsProxyClient`.
3. **Outbox Scheduled Drain Test**: Verify `OutboxDrainWorker` drains `result_submitted` outbox events and triggers `SessionRunService.runSession`.
4. **Full Affected Test Suite**: Run `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2` or `pnpm test`.
