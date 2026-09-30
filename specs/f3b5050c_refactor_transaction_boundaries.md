# Refactor Transaction Boundaries in Factory Service Plan

## Summary

This refactoring addresses a critical transactional defect in `factory-service` where `SessionRunService.runSession()` is annotated `@Transactional`, keeping a single Neo4j transaction open across the entire DAG step execution loop—including slow external proxy calls (AgentOS) and local process execution. When external operations exceed Neo4j's embedded transaction timeout (default 30s), the Neo4j transaction dies, causing subsequent step state/evidence writes to fail with "Cannot run more queries in this transaction" (triggering an unhandled 500 error).

The resolution tightens transactional boundaries so external execution NEVER runs inside an active Neo4j transaction, introduces short isolated transactions (`REQUIRES_NEW`) for claim/reservation, durable state/evidence persistence, and emergency error recovery, decouples human interaction reply from session resumption, and enforces strict CAS update result checks with explicit `REVISION_CONFLICT` / `WorkflowException` handling.

---

## Targeted Files & Modifiability Rules

### Touch
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/Neo4jWorkflowRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/Neo4jHumanInteractionRepository.kt` (if needed for CAS checks/helper)
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/PersistenceConfigProperties.kt`
- Test files under `factory-service/src/test/kotlin/io/whozoss/factory/...`

### DO NOT TOUCH
- `WorkflowSseHub.kt`
- `WorkflowSseController.kt`
- `factory/dashboard/js/*` or any UI files
- DB migrations or release pipelines.
- Keep existing SSE publication calls in place with their current signatures.

---

## Architectural & Implementation Plan

### 1. Transaction Boundaries & External Execution Isolation

#### A. `SessionRunService.kt`
- Remove `@Transactional` annotation from `runSession()`. `runSession()` will act as a orchestrator loop operating outside any broad transaction.
- Retain `@Transactional(readOnly = true)` on `sessionState()`.
- Refactor DAG execution loop in `runSession()`:
  - Iteration steps (evaluating ready steps, updating per-step states, reading definitions/instances) run as discrete, short-lived transactional calls or delegating to transactional methods on inner services / repositories.
  - Auto-oracle calls in `runApplicableOracles` must run in short, isolated transactional units (or each oracle run executes in its own short boundary).

#### B. `CapabilityExecutionService.kt`
- Remove `@Transactional` from `resolveAndRecord()`.
- Split `resolveAndRecord` (and persona handlers like `resolveAgent`) into a 3-phase execution model:
  1. **Phase 1: Transaction 1 - Claim & Reservation (`REQUIRES_NEW`)**
     - Method `claimStepAttempt(scope, namespaceId, workflowId, step, ticket)` annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
     - Validates active instance status and ownership (checks `running -> ready` or current status/revision).
     - Inserts the `AgentStepAttemptRecord` (status = "running", revision = 1) or records initial claim state.
     - Mints capability token if applicable.
     - Returns claim payload (attemptId, caseId, capabilityToken, brief).
  2. **Phase 2: External Execution (No Transaction)**
     - Calls `resolver.resolve(...)` (HTTP calls to AgentOS proxy, process invocation, local verification).
     - Executed strictly outside any Spring/Neo4j active transaction.
     - Catches all transport/execution exceptions locally to produce a `CapabilityOutcome.AgentFailed` or error outcome rather than letting uncaught exceptions leak without recording evidence.
  3. **Phase 3: Transaction 2 - Record Terminal State & Evidence (`REQUIRES_NEW`)**
     - Method `recordTerminalAttempt(scope, namespaceId, workflowId, step, outcome, attemptId, ...)` annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
     - Validates step/run ownership and step status before completing.
     - Terminalizes the attempt record (`completed` or `failed`).
     - Appends code transitions or human interaction requests (for non-agent kinds) or evidence items (`agent-turn`, `code-verification`).
     - Returns completed `CapabilityExecution`.

#### C. `Neo4jPersistenceConfiguration.kt` & `PersistenceConfigProperties.kt`
- Verify `Neo4jTransactionManager` bean configuration support for propagation (`REQUIRES_NEW`).
- Ensure `PersistenceConfigProperties` `embeddedTransactionTimeoutSeconds` (default 30s) is mapped properly and can be customized in tests or properties without affecting short transaction boundaries.

---

### 2. Human Reply & Continuation Decoupling

#### `WorkflowService.kt`
- Refactor `replyInteraction()`:
  - **Transaction 1 (Short)**: Annotate `replyInteraction` with `@Transactional`. It performs:
    - Loading interaction and verifying step/instance state.
    - Recording `human-decision` evidence.
    - Applying transition to workflow instance and updating interaction to `closed`.
    - Publishing SSE event.
    - Transaction commits at method end.
  - **Post-Transaction Resumption**:
    - Extract `resumeCheckpointSession` call so it is invoked AFTER Transaction 1 commits.
    - Ensure resumption runs in a SEPARATE execution context or non-joining transaction context (e.g. `Propagation.NEVER` or via a self-autowired proxy / asynchronous executor / non-transactional helper method) so `SessionRunService.runSession` does not join or inherit the reply transaction.

---

### 3. Error & Exception Handling & Emergency Recovery

#### A. Emergency Recovery Writes
- In `SessionRunService.executeStep`:
  - When `capabilityExecutionService.resolveAndRecord` or step execution throws an infrastructure/external exception outside a transaction:
  - Catch the exception OUTSIDE any dead Neo4j transaction.
  - Call an emergency recovery helper `recordStepFailure(...)` annotated with `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
  - In `recordStepFailure`:
    - Record failure evidence (`recordFailureEvidence`).
    - Append step failure transition (`transition(..., FAILED)`).
    - Update step status in step states (`setStatus(..., FAILED)`).
  - Return `WorkflowStatuses.FAILED` cleanly without raising 500 or masking the original error cause.

#### B. Isolated Auto-Oracle Execution
- In `runApplicableOracles`:
  - Wrap each oracle execution call in isolated try-catch boundaries.
  - Oracle execution and evidence publishing must run in short, clean transactions so a failing/timing-out oracle does not leave the overall session transaction in an unrecoverable state.

---

### 4. Strict CAS Verification & Conditional Updates

#### A. Repository Update Return Value Checks
- Audit all CAS / conditional update operations in `Neo4jWorkflowRepository` and `Neo4jHumanInteractionRepository`:
  - `updateInstance(...)`: Returns `Boolean` (from `casUpdateInstance > 0`).
  - `setInstanceStatus(...)`: Returns `Boolean` (from `casUpdateInstanceStatus > 0`).
  - `updateStepStatus(...)`: Returns `Boolean` (from `casUpdateStatus > 0`).
  - `update(...)` in `Neo4jHumanInteractionRepository`: Returns `Boolean` (from `casUpdate > 0`).
  - `setProjectionLifecycle(...)`: Ensure return status is checked or boolean returned.
- Update caller methods in `SessionRunService` and `WorkflowService`:
  - In `SessionRunService.persistProjection`:
    - Check the return value of `repository.updateInstance(...)`.
    - If `false`, throw `workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "Instance projection revision conflict.")`.
  - In `SessionRunService.setStatus`:
    - Update `repository.updateStepStatus` or check `repository.upsertStepState`. If CAS check fails, throw `REVISION_CONFLICT`.
  - In `WorkflowService.replyInteraction`, `openInteraction`, `transition`, `codeTransition`:
    - Explicitly check `repository.updateInstance` and `interactionRepository.update`. If false, throw `workflowException(WorkflowErrorCodes.REVISION_CONFLICT, ...)`.

#### B. Step/Run Ownership Verification
- Before executing a step or recording results, verify the step's expected revision and state (`ready` -> `running`).
- Prevent abandoned or stale runs/steps from updating state or resuming if another process/turn has modified the revision or status.

---

## Detailed Step-by-Step Implementation Outline

### Step 1: Configuration & Repository Hardening
1. Review `Neo4jPersistenceConfiguration.kt` and `PersistenceConfigProperties.kt`. Ensure `@EnableTransactionManagement` (if needed) or Spring transaction proxying works cleanly with `Propagation.REQUIRES_NEW`.
2. Review `Neo4jWorkflowRepository.kt` and `Neo4jHumanInteractionRepository.kt` to ensure all CAS Cypher updates accurately return modified row counts (`> 0`) and methods expose boolean return values.

### Step 2: CapabilityExecutionService Phase Decomposition
1. In `CapabilityExecutionService.kt`, remove class/method-level outer `@Transactional`.
2. Add `@Transactional(propagation = Propagation.REQUIRES_NEW)` method `claimAgentAttempt(...)` for Phase 1.
3. Keep AgentOS / process execution in Phase 2 strictly non-transactional with try-catch around `resolver.resolve(...)`.
4. Add `@Transactional(propagation = Propagation.REQUIRES_NEW)` method `recordTerminalAgentAttempt(...)` for Phase 3.
5. Implement similar 3-phase/short-transaction handling for code verification and human checkpoint recording.

### Step 3: SessionRunService Un-transactionalization & Orchestration
1. In `SessionRunService.kt`, remove `@Transactional` from `runSession()`.
2. Extract inner atomic operations into methods marked `@Transactional(propagation = Propagation.REQUIRES_NEW)` or `@Transactional`:
   - `loadProgress` / initial step state setup.
   - `executeStep` phase 1 claim, phase 2 external call, phase 3 completion.
   - `recordStepFailure` helper (annotated `REQUIRES_NEW`).
   - `runApplicableOracles` execution units.
   - `persistProjection` (annotated `REQUIRES_NEW` with strict CAS result check).
3. Check all CAS return values in `persistProjection` and `setStatus`; throw `REVISION_CONFLICT` on failure.

### Step 4: WorkflowService Human Reply Decoupling & CAS Hardening
1. In `WorkflowService.kt`, update `replyInteraction`:
   - Ensure the database writes (evidence, instance transition, interaction update) run in a short transaction.
   - Check `interactionRepository.update(...)` return value; if `false`, throw `WorkflowException(REVISION_CONFLICT)`.
   - Call `resumeCheckpointSession` OUTSIDE the reply transaction (e.g. using a post-commit hook or explicit non-transactional resumption call).

### Step 5: Integration & Unit Test Verification
Create/update test cases under `factory-service/src/test/`:

1. **Test Case 1: Neo4j Timeout + Slow Capability Execution**
   - Inject a mock/stub `CapabilityResolver` that sleeps longer than `embeddedTransactionTimeoutSeconds` (or configure a short 1s timeout in test properties).
   - Execute `runSession()`.
   - Assert: No "Cannot run more queries in this transaction" exception is thrown. Failure/evidence is successfully recorded in a fresh transaction, and step state is updated to `FAILED` without 500 error masking.

2. **Test Case 2: Human Response Durability & Resumption Decoupling**
   - Execute `replyInteraction` with a stubbed `SessionRunService` or `resolver` that throws an exception during resumption.
   - Assert: Human decision, evidence, and interaction `closed` status remain durably committed in Neo4j even when resumption fails.

3. **Test Case 3: Concurrent Step Claims**
   - Simulate two concurrent `runSession` calls on the same ready step using Spring beans.
   - Assert: Atomic claim reservation ensures external execution runs exactly ONCE; the second caller receives revision conflict or detects step is already running/processed.

4. **Test Case 4: Rejected Revision on CAS Failure**
   - Simulate a CAS snapshot/revision mismatch during instance update or step status update.
   - Assert: `REVISION_CONFLICT` exception is thrown, and no stale status/projection is published via SSE.

---

## Verification Strategy

- Run full test suite:
  `pnpm nx test factory-service`
- Verify linting & affected tests:
  `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
  `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
