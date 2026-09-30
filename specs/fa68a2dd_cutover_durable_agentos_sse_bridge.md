# Cutover durable SSE AgentOS bridge as primary path in CapabilityExecutionService

## Goal

Cutover the AgentOS durable SSE bridge (`AgentOsExecutionAdapter` + `DurableAgentAttemptService`) to be the **PRIMARY, MANDATORY, NON-OPTIONAL** execution driver in `CapabilityExecutionService` for `agent` steps. Historical HTTP polling (`HttpAgentOsProxyClient` / `AgentOsAgentTurnCapability`) is demoted strictly to an explicit fallback / reconciliation mechanism and legacy code path. All 20 acceptance criteria across sequencing, SSE reconnection, idempotency, verdicts, transaction isolation, and finalization must be verified / implemented and passing.

---

## Architectural Intent & Boundaries

1. **Mandatory Dependencies in `CapabilityExecutionService`**:
   - `DurableAgentAttemptService` and `AgentOsExecutionAdapter` become mandatory constructor parameters (non-null in primary constructor).
   - If fallback polling is retained as an explicit secondary method, `agentOsAgentTurnCapability` / `HttpAgentOsProxyClient` or a fallback mode parameter may be supplied optional/defaulted, but the SSE bridge is the primary non-null path.
   - **Crucial Refactoring Constraint**: All callers and test instantiations of `CapabilityExecutionService` across `factory-service` MUST be updated to pass these required dependencies (or suitable test stubs/fakes like `FakeAdapter` / autowired beans).
   - Compilation must be verified early using:
     `cd factory-service && ./gradlew compileKotlin compileTestKotlin`

2. **Primary Path in `CapabilityExecutionService.kt`**:
   - `resolveAgent` delegates directly to `resolveAgentViaAdapter` as the primary path for `agent` steps.
   - Demote `resolveAgentViaPolling` to an explicit fallback / reconciliation helper (do NOT delete polling code, keep it available if fallback is explicitly requested/triggered).

3. **Spring Configuration Updates**:
   - In `AgentOsAdapterProperties.kt`: default `factory.adapter.agentos.enabled` to `true`.
   - In `AgentOsAdapterConfiguration.kt`: ensure the `AgentOsExecutionAdapter` bean is created by default when `enabled` is `true` (or when property is missing / defaults to true).
   - Ensure autowiring in Spring contexts (e.g. `@SpringBootTest` integration tests like `WorkflowServiceIntegrationTest`, `SessionSequencerIntegrationTest`, `DurableAgentOsBridgeIntegrationTest`) cleanly wires `AgentOsExecutionAdapter` and `DurableAgentAttemptService` into `CapabilityExecutionService`.

4. **Invariants & Definition of Done**:
   - **No Polling in Nominal Execution**: Nominal execution streams via SSE with durable claim/lease fencing.
   - **No Neo4j Transaction during Remote Turn**: Remote turns execute completely un-transacted (Phase 2), bounded by short `REQUIRES_NEW` transactions for Phase 1 (claim) and Phase 3 (finalize/evidence).
   - **Idempotency & Re-entrancy**: Outages, restarts, or mid-flight crashes never duplicate turns or recreate cases; attempts are keyed by `attemptId` (and `stableAttemptId(workflowId, stepId)` / `stableCaseId(workflowId, stepId)`).
   - **Strict Dependency Input Sourcing & Phase Gates**: Step B starts ONLY after Step A's `agent-result` evidence is committed to Neo4j. Step B's inputs are sourced strictly from Step A's persisted durable evidence outputs (`facts["outputs"]`), never from uncommitted messages or raw turn text.
   - **Determinate Verdict Sourcing**: Indeterminate/unknown states never infer success; valid structured outputs produce `AgentCompleted`, while missing/unparseable outputs or error states yield `AgentFailed` or `AgentDeferred`.
   - **SSE Protocol & Dedup**: Event deduplication via `eventId`/high-watermark; zero changes required to AgentOS core (`caseEvent`/`caseFlow`).

---

## Technical Strategy & Changes

### Task 1: Update `CapabilityExecutionService` Constructor & Primary Dispatch
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
  - Modify class declaration to make `durableAgentAttemptService: DurableAgentAttemptService` and `agentOsExecutionAdapter: AgentOsExecutionAdapter` required constructor parameters (non-null).
  - In `resolveAgent`: invoke `resolveAgentViaAdapter` as the primary path.
  - Update any fallback logic or keep `resolveAgentViaPolling` accessible for fallback scenario testing.

### Task 2: Update All Callsites and Test Instantiations in `factory-service`
- **Files to update**:
  - `factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityExecutionCapabilityIssuanceTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityExecutionIntegrationTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/SessionDefinitionImportIntegrationTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/SessionSequencerIntegrationTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/DurableAgentOsBridgeIntegrationTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/TransactionBoundaryIntegrationTest.kt`
  - Any other test classes or Spring configuration instantiating `CapabilityExecutionService`.
- Use test double adapters (e.g. `FakeAgentOsExecutionAdapter` or mock/stub adapter) where integration tests need isolated capability resolution.

### Task 3: Spring Property Defaults & Bean Wiring
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsAdapterProperties.kt`
  - Change `val enabled: Boolean = false` to `val enabled: Boolean = true`.
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsAdapterConfiguration.kt`
  - Ensure `@ConditionalOnProperty` matches `matchIfMissing = true` or `havingValue = "true"`.
  - Ensure Spring context autowires `AgentOsExecutionAdapter` and `DurableAgentAttemptService` cleanly into `CapabilityExecutionService`.

### Task 4: Complete & Verify Acceptance Tests (20 Acceptance Criteria)
Verify or add explicit test cases in `DurableAgentOsBridgeIntegrationTest` (or dedicated test classes under `factory-service/src/test/kotlin/io/whozoss/factory/workflow/`):

1. **Sequencing & Dependency Gates**:
   - **Criterion 1**: Blocked A -> B never starts.
   - **Criterion 2**: A completes with identifiable output -> B starts once & receives exact durable output.
   - **Criterion 3**: A fails -> B blocked.
   - **Criterion 4**: A waiting human checkpoint -> B not started.
   - **Criterion 5**: Concurrent execution requests -> single attempt/lease owns execution.

2. **SSE Stream & Reconnection Resilience**:
   - **Criterion 6**: SSE drop during A + reconnect -> no duplicate turn, result received.
   - **Criterion 7**: Factory process restart during A -> observation resumed/reattached without recreating work or new case.
   - **Criterion 8**: Connection established after A finished -> replay/snapshot enables finalization.
   - **Criterion 9**: Duplicate event received (`eventId` duplicate) -> single finalization.
   - **Criterion 10**: Stale event from previous turn -> current attempt remains unchanged.

3. **Idempotency, Recovery & Fencing**:
   - **Criterion 11**: Crash after case creation before local persistence -> case found by stableCaseId & reattached.
   - **Criterion 12**: Crash after message acceptance before HTTP response -> no second turn dispatched (`startTurn` skipped if already started).
   - **Criterion 13**: Crash after AgentOS result before Factory commit -> recovery/reconciliation yields single finalization.
   - **Criterion 14**: Worker lost lease attempts finalize -> fencing rejection (`AttemptClaimConflictException`).

4. **Verdicts & Outcome Mapping**:
   - **Criterion 15**: IDLE state with unanswered question -> `AgentWaitingHuman` / checkpoint.
   - **Criterion 16**: IDLE state without valid structured output -> `AgentIndeterminate`.
   - **Criterion 17**: ERROR / KILLED / timeout -> `AgentFailed` / `AgentIndeterminate`, never implicit success.
   - **Criterion 18**: Valid structured output -> evidence (`agent-result`) and outputs persisted in DB before B activation.

5. **Transactions & Database Durability**:
   - **Criterion 19**: Remote turn duration exceeds Neo4j transaction timeout -> completes normally, 0 open Neo4j transaction during HTTP/SSE turn.
   - **Criterion 20**: Finalization failure (e.g. DB commit error) -> remote result preserved/reconcilable on retry, no false cockpit notification.

### Task 5: Documentation & Cutover Note
- **File**: `app_docs/cutover_durable_agentos_sse_bridge.md`
  - Document the default behavior changes (`enabled=true`, SSE adapter as primary non-null dependency).
  - Include instructions for falling back or invoking legacy polling if required for troubleshooting.
  - Detail the test report covering all 20 acceptance test criteria.

---

## Execution Plan & Early Verification Steps

1. **Step 1: Code Modifications**
   - Update `AgentOsAdapterProperties.kt` (`enabled = true`).
   - Update `CapabilityExecutionService.kt` (require `DurableAgentAttemptService` and `AgentOsExecutionAdapter`, make `resolveAgentViaAdapter` the primary default path).
   - Update test files and Spring config files to pass required dependencies to `CapabilityExecutionService`.

2. **Step 2: Early Compilation Verification**
   - Run: `cd factory-service && ./gradlew compileKotlin compileTestKotlin`
   - Ensure zero compilation errors.

3. **Step 3: Integration Test Verification**
   - Run: `cd factory-service && ./gradlew test`
   - Ensure all acceptance criteria tests in `DurableAgentOsBridgeIntegrationTest` (and all other `factory-service` tests) pass.

4. **Step 4: Handoff & Documentation**
   - Write `app_docs/cutover_durable_agentos_sse_bridge.md`.
   - Copy plan to `specs/<adw_id>_cutover_durable_agentos_sse_bridge.md`.

---

## Builder Notes
- Always run Gradle commands from inside `factory-service/` using `./gradlew` (e.g. `cd factory-service && ./gradlew test`).
- Ensure no Neo4j transaction is active during SSE streaming calls in Phase 2.
- Verify that `DurableAgentOsBridgeIntegrationTest` directly covers all 20 criteria or add missing sub-test cases with explicit assertion descriptions matching criteria 1 to 20.
