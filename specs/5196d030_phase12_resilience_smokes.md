# Phase 12 — Resilience & Invariant End-to-End Smokes

## Goal

Add an end-to-end **resilience smoke test suite** to `factory-service` that proves the
system's safety invariants hold under failure/stress scenarios spanning Phases 0–11.
Every test asserts an invariant **explicitly** (non-inferred terminal status,
immutability, idempotency, rejection, no double-unblocking, bounded recovery packet,
no implicit reopening). Scenarios that need unbuilt components are added as **skipped**
tests carrying a `@Disabled` reason that documents the missing dependency.

This is a **test-only** change. No production code, no migrations, no release-pipeline
files are touched. If a smoke reveals a genuine production gap, STOP and report it in the
build notes rather than patching production code under cover of a "test" task.

## Hard constraints (do not violate)

- **Test-only.** Only add files under `factory-service/src/test/kotlin/...`. Do NOT modify
  any `src/main` production code, DB migrations, Flyway, Neo4j schema, Gradle release config
  or the Nx pipeline.
- Reuse the **existing** harnesses, fakes and service beans listed below. Do NOT invent a new
  persistence stack; copy the proven patterns.
- No semicolons, single quotes, 120-col lines, explicit return types on public functions,
  kebab-case file names where the repo uses them (Kotlin files are PascalCase here — match the
  neighbours in `factory-service/src/test`).
- Scratch output → `/tmp`, never into the repo tree.
- Conventional commit message (`test(factory-service): ...`).
- Each test must assert the **named invariant**, not merely that code runs. A smoke that
  cannot assert its invariant with existing hooks must be a documented `@Disabled` skip, not a
  weak always-green test.

## Verification

- `cd factory-service && ./gradlew test` (or `pnpm nx test factory-service`) is green.
- The new `resilience` test class(es) run and the targeted (non-disabled) smokes pass.
- The factory's own suite is run post-build:
  `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`.
- Lint/build if desired: `pnpm nx affected -t lint`/`build --base="$(cat /work/data/baseline)"`.

---

## Where things live (recon results — use these exact types)

### Test harness patterns (two proven styles)

1. **Spring Boot integration, embedded Neo4j, real beans + a stateful fake adapter.**
   Base class: `io.whozoss.factory.Neo4jDomainIntegrationTest` (extends
   `io.whozoss.factory.Neo4jIntegrationTest`, which is
   `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = [FactoryServiceApplication, SharedNeo4jIntegrationTestConfiguration])`,
   `@ActiveProfiles("test","embedded-neo4j")`, `@Import(EmbeddedNeo4jTestConfiguration)`).
   Subclasses `@Autowired` the real services and `@BeforeEach` clears the graph via
   `Neo4jTestSupport.clearDatabase(neo4jDriver)`.
   Canonical examples to copy:
   - `src/test/kotlin/io/whozoss/factory/workflow/DurableAgentOsBridgeIntegrationTest.kt`
     (real `DurableAgentAttemptService`, `WorkflowService`, `SessionRunService`,
     evidence/interaction repos + a stateful in-test `FakeAdapter : AgentOsExecutionAdapter`;
     drives `BridgeRecoveryWorker(...).recover()` to simulate a **factory restart /
     startup recovery sweep**).
   - `src/test/kotlin/io/whozoss/factory/agentattempt/BridgeRecoveryWorkerTest.kt`
     (same pattern; shows the `FakeAdapter` shape — overrides
     `createOrRecoverExecution`, `startTurn`, `observeTurn`, `reconcile`).
   - `src/test/kotlin/io/whozoss/factory/workstream/ControllerCaseServiceIntegrationTest.kt`
     and `WorkstreamProjectionIntegrationTest.kt` (controller case + read-only projection).

2. **Hand-wired restart across a real Neo4j engine reboot (no Spring Boot).**
   Canonical example: `src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultDurabilityTest.kt`.
   It boots `Neo4jBuilders.newInProcessBuilder(tempDir).withDisabledServer().build()`, wires an
   `AnnotationConfigApplicationContext` with `@EnableNeo4jRepositories`, runs through the real
   `AgentStepResultService`, then `stopAndSnapshot()` (graceful DBMS shutdown + file copy) and
   boots a **brand-new** DBMS over the snapshot via `builder.copyFrom(snapshot)`. Everything
   asserted post-reboot is reloaded from disk. Companion:
   `AgentStepQuestionDurabilityTest.kt` (question survives restart; answer supersedes N→N+1).

Prefer style **1** for most Phase 12 smokes (it exercises production wiring and the recovery
worker). Use style **2** only where a true on-disk restart boundary is the invariant under test
(result-channel / question durability), and in that case **drive the existing durability tests'
proven helpers rather than reinventing the snapshot dance** — i.e. model the new case on the
existing class.

### Recovery / restart machinery

- `io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker` — `recover(limit): RecoveryReport`;
  `onApplicationReady()` runs it on boot. In `recoverAttempt` it calls `adapter.reconcile(caseId)`
  and branches on the verdict: `Succeeded` → finalize via fresh lease (walks the state machine),
  `WaitingHuman` → `reconcileWaitingHuman`, `Indeterminate` → re-observe / redrive / leave waiting,
  `Failed` → finalize. **It never infers a terminal success from silence or an unreachable runtime.**
  Constructed in tests as `BridgeRecoveryWorker(durableAgentAttemptService, fakeAdapter, evidenceRepository)`.
- `io.whozoss.factory.agentattempt.service.ResultChannelRecoveryWorker` — `onApplicationReady()` →
  `service.reconcileOnStartup()` (result aggregate startup reconcile).
- `io.whozoss.factory.adapter.agentos.ActiveCaseRegistry` — in-memory registry of live cases.

### Verdict vocabulary (the "never succeed by silence/unreachable" invariant)

- `io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict` — sealed interface with
  `Succeeded`, `WaitingHuman`, `Failed(code, message, evidence)`, `Interrupted(reason, …)`,
  `Indeterminate(reason, evidence)`.
- `io.whozoss.factory.adapter.agentos.VerdictDeriver.derive(events, DerivationContext): AgentOsExecutionVerdict?`
  — returns `null` while non-quiescent; `IDLE` with no structured result →
  `Indeterminate(AGENT_NO_STRUCTURED_RESULT)` (**never `Succeeded`**); `ERROR`/`KILLED` → `Failed`.
  Relevant reason constants: `RUNTIME_UNREACHABLE`, `OBSERVATION_TIMEOUT`,
  `RECONNECT_BUDGET_EXHAUSTED`, `AGENT_NO_STRUCTURED_RESULT`, `AGENT_CASE_ERROR`, `AGENT_CASE_KILLED`.
- AgentOS "down/unreachable" test double: either make the in-test `FakeAdapter.reconcile` throw /
  return `Indeterminate(RUNTIME_UNREACHABLE, …)`, or use
  `src/test/kotlin/io/whozoss/factory/adapter/agentos/FakeAgentOsSseServer.kt` and enqueue an empty
  `Script()` (connection closes immediately = "dead" case) or point the client at a closed port.

### Step-result single-use channel + idempotency

- `io.whozoss.factory.agentattempt.service.AgentStepResultService` — `issue(scope, identity, now)`
  returns a capability token; `submit(scope, token, business, observed, idempotencyKey?, now)`
  returns an outcome with `created`/`idempotent`/`resultId`/`resultHash`.
  - Identical replay → `idempotent == true`, same `resultId`/`resultHash`, still **one** result
    row + **one** outbox event (`result_submitted`, `pending`).
  - Divergent replay on the same token → `ResultSemanticCollisionException`
    (`errorCode = RESULT_SEMANTIC_COLLISION`).
  - Divergent replay via idempotency key → `IdempotencyKeyCollisionException`
    (`errorCode = IDEMPOTENCY_KEY_COLLISION`).
  - Re-issue of an already-issued capability → `ResultCapabilityAlreadyIssuedException`.
  - Idempotency record keyed by `IdempotencyRecordNode.compositeId(orgId, key)`.
- The terminal attempt stays `"completed"` and immutable across replays and across a restart.

### Human-question flow (N / N+1 superseding, no double-unblock)

- `io.whozoss.factory.agentattempt.service.AgentStepQuestionService` —
  - `ask(scope, token, attemptId, questionNode, observedCaseId, observedAgentName, observedNamespaceId, now)`
    → `AgentStepQuestionAsked(attemptId, interactionId, status, idempotent)`. Idempotent re-ask of
    the same deterministic question returns the same interaction with `idempotent = true`.
  - `answer(scope, namespaceId, workflowId, interactionId, expectedRevision, answer, actorId, now)`
    → `AgentStepQuestionAnswered(..., supersededAttemptId, ...)`. In **one transaction** it closes
    the interaction (CAS single-use), supersedes attempt **N** (terminal/immutable) and registers
    attempt **N+1** (pending). A second answer loses the CAS / finds the interaction closed and
    throws `QuestionAlreadyAnsweredException` — it can **never** create an attempt N+2
    (= no double unblocking). A stale `expectedRevision` → `RevisionConflictException`.
    If predecessor attempt isn't `WAITING_HUMAN` → `QuestionSupersedeConflictException`.
  - Status/const refs: `AgentAttemptStatus.WAITING_HUMAN`, `INTERACTION_WAITING`,
    `AGENT_QUESTION_INTERACTION_TYPE`, `WAITING_HUMAN_STATUS`.
  - Durability precedent to model restart case on:
    `AgentStepQuestionDurabilityTest` test
    `` `a waiting step question survives a restart and its answer supersedes N and persists N+1` ``
    (asserts `answered.supersededAttemptId == attemptId`, then a second
    `answer(... 999, "again", "bob", ...)` throws `QuestionAlreadyAnsweredException`).

### Controller case renewal + bounded recovery packet (P9)

- `io.whozoss.factory.workstream.ControllerCaseService` —
  - `startControllerCase(caller, workstreamId, StartControllerCaseRequest)` →
    `ControllerCaseExecution` (sequence 1, `status = ACTIVE`, `controllerAgentRef` from the
    workstream, `contextSummary = packageBuilder.toBoundedJson(contextPackage)`).
  - `compactControllerCase(caller, workstreamId, CompactControllerCaseRequest)` — the **renewal**:
    archive the active case + start a fresh one with the **same** `controllerAgentRef`
    (read from the active case, never changes), next sequence, resumption package rebuilt from the
    fresh projection. `INVALID_COMPACTION_REASON` if reason too long; `NO_ACTIVE_CONTROLLER_CASE`
    if none active; `CONTROLLER_CASE_ALREADY_ACTIVE` on double start.
- `io.whozoss.factory.workstream.projection.ControllerResumptionPackageBuilder` —
  `build(projection)` + `toBoundedJson(pkg)` enforcing `ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES`
  (8192) and per-section caps (`MAX_WORKFLOW_ITEMS=10`, `MAX_HUMAN_ACTIONS=10`, `MAX_BLOCKERS=10`,
  `MAX_RECENT_CHANGES=10`, `MAX_COMPACTION_REASON_CHARS=500`).
  **Bounded** = serialized package ≤ 8192 UTF-8 bytes (compact-or-reject).
- Harness/seed pattern: `ControllerCaseServiceIntegrationTest` (seeds via `WorkstreamService`,
  `DurableAgentAttemptService`, oracle/env/interaction repos; asserts controller stability + byte cap).

### Plan-change governance gate (P8) — incompatible proposal rejected, no silent rewrite

- `io.whozoss.factory.planchange.service.PlanChangeProposalService` —
  `submit(scope, actorId, PlanChangeSubmitCommand)` classifies via `PlanChangeClassifier.classify`
  and records a proposal (never auto-mutates the plan); `decide(...)` records a decision.
- `io.whozoss.factory.planchange.domain.PlanChangeKind` — `RETRY_NO_PLAN_CHANGE`, `PATH_SELECTION`,
  `OPTIONAL_STEP_ACTIVATION` (self-applicable), `DEPENDENCY_CHANGE_PROPOSAL`,
  `SCOPE_CHANGE_PROPOSAL`, `NEW_STEP_PROPOSAL`, `CONTRACT_OR_ORACLE_CHANGE_PROPOSAL`.
- `io.whozoss.factory.planchange.domain.GovernanceGateEvaluator` —
  `recommendedVerdict(kind): PlanChangeDecisionStatus` (self-applicable → `AUTO_APPLIED`;
  dependency/scope → `GATE_REQUIRED`; new-step/contract-oracle → `REQUIRES_NEW_DEFINITION`);
  `assertDecisionAllowed(kind, requested)` throws (409 `PLAN_CHANGE_GATE_REQUIRED`) if a caller
  tries to `AUTO_APPLIED` a non-self-applicable kind. This is the "**no silent rewrite**" guard:
  an incompatible (e.g. `CONTRACT_OR_ORACLE_CHANGE_PROPOSAL`) proposal can never be auto-applied;
  it requires a gate/new definition.
- `io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus` — `PENDING_VALIDATION`,
  `AUTO_APPLIED`, `GATE_REQUIRED`, `REQUIRES_NEW_DEFINITION`, `REJECTED`.
- Tests to copy: `GovernanceGateEvaluatorTest.kt`, `PlanChangeClassifierTest.kt`,
  `PlanChangeValidationTest.kt`, `PlanChangeProposalControllerIntegrationTest.kt`.

### Terminal workflow sealing (P10) — no implicit reopening

- `io.whozoss.factory.workflow.service.WorkflowService.assertNotSealed(instance)` — throws
  `WorkflowErrorCodes.WORKFLOW_SEALED` (409) when `WorkflowStatuses.isTerminal(runStatus)`
  (`COMPLETED`/`FAILED`/`CANCELLED`). `restore()` only un-soft-deletes; it never reopens a terminal
  run. A terminal run is sealed; resuming spawns a NEW workflow linked to the predecessor.
- `io.whozoss.factory.workflow.domain.WorkflowStatuses.TERMINAL = {COMPLETED, FAILED, CANCELLED}`,
  `isTerminal(status)`. Error code `WorkflowErrorCodes.WORKFLOW_SEALED`.

### Workstream read-only tools (P6) + gate command tool (P7)

- The **tools** themselves live in the AgentOS bridge plugin
  (`agentos/agentos-factory-bridge-plugin/src/.../factorybridge/tools/`): read-only tools
  `FactoryGetWorkflowTool`, `FactoryGetStepAttemptsTool`, `FactoryGetWorkstreamTool`,
  `FactoryListWorkflowsTool`, `FactoryGetBlockersTool`, `FactoryGetRequiredHumanActionsTool`;
  command tools `FactoryRequestTransitionTool`, `FactoryRequestAgentRetryTool`,
  `FactoryProposePlanChangeTool`, etc. (`FactoryCommandToolsTest.kt` is the Kotest reference).
- **Because Phase 12 tests live in `factory-service`**, smoke the **server-side services the tools
  call** rather than the plugin tools (which are a different Gradle module). Concretely:
  - **P6 read-only answers correctly** → drive `WorkstreamProjectionService` /
    `DurableAgentAttemptService` through the proven `WorkstreamProjectionIntegrationTest` seeding
    pattern and assert the returned counts/summaries are correct and that no mutation occurred
    (ETag/revision stable across repeated reads).
  - **P7 opening a gate via a command tool** → drive the governance gate server-side: a
    `GATE_REQUIRED` plan-change decision (`PlanChangeProposalService` + `GovernanceGateEvaluator`)
    OR a human-interaction/transition request through `WorkflowService` that opens a human gate.
    Assert the gate transitions state (status moves to the gated state; the invariant that a gate
    cannot be bypassed via `AUTO_APPLIED` holds).

---

## Deliverables

### New test package and class(es)

Create the resilience package:
`factory-service/src/test/kotlin/io/whozoss/factory/resilience/`

Preferred split (keeps compile units focused and context-cacheable — all Spring Boot subclasses of
`Neo4jDomainIntegrationTest` share ONE cached context):

1. `Phase12ResilienceSmokesTest.kt` — `: Neo4jDomainIntegrationTest()`, the Spring-Boot style
   smokes (scenarios 1,2,3-dup,4-answer,5,6,7,8). This is the primary file.
2. (Optional) `Phase12RestartDurabilitySmokesTest.kt` — standalone (no Spring Boot), modelled on
   `AgentStepResultDurabilityTest` / `AgentStepQuestionDurabilityTest`, only if you implement the
   true on-disk reboot variants of scenarios 1 & 3 & 4 there. If the Spring-Boot
   `BridgeRecoveryWorker.recover()` restart simulation covers the invariant adequately, keep
   everything in file 1 and skip this file.

Each test must have a KDoc block naming the Phase(s) and the invariant it proves, mirroring the
style of the existing durability/bridge tests.

### Scenario-by-scenario test specification

Name tests with backtick sentences stating the invariant. Each must assert the invariant explicitly.

1. **Factory restart during attempt → recovery clarifies, never infers terminal success (P2/P3).**
   - Model on `DurableAgentOsBridgeIntegrationTest` §5 (`recover()` as the restart boundary).
   - Seed a running/non-terminal durable attempt. Run `BridgeRecoveryWorker(...).recover()` with a
     `FakeAdapter` whose `reconcile` returns a NON-terminal/`Indeterminate` or `WaitingHuman` verdict.
   - Assert: the recovered attempt's status is clarified/left waiting — NOT `completed`/success;
     `startTurns` not duplicated; the recovery report reflects WAITING/RESUMED, never a fabricated
     `FINALIZED` success. Assert no success verdict was synthesized from silence.

2. **AgentOS unreachable/down → classified Indeterminate, NEVER succeeded/pass (P3).**
   - `FakeAdapter.reconcile` throws (connection refused) or returns
     `AgentOsExecutionVerdict.Indeterminate(VerdictDeriver.RUNTIME_UNREACHABLE)`.
   - Run recovery (or a direct observe). Assert the resulting verdict/attempt state is
     `Indeterminate` (or stays non-terminal) and is **not** `Succeeded` and **not** marked
     pass/completed. Add a direct `VerdictDeriver.derive(...)` unit assertion that an empty/
     non-quiescent event list → `null` and an IDLE-without-result → `Indeterminate(AGENT_NO_STRUCTURED_RESULT)`,
     never `Succeeded`.

3. **Late + duplicate step-result submission → verdict unchanged, replay idempotent (P1/P10).**
   - Via `AgentStepResultService`: `issue` then `submit` once (`created == true`).
   - Submit the identical business result again (late/duplicate) → `idempotent == true`, same
     `resultId`/`resultHash`; assert still exactly ONE result row and ONE outbox event; attempt
     stays `"completed"`. A divergent replay → `ResultSemanticCollisionException`
     (and `IdempotencyKeyCollisionException` via the key path).
   - Pair with a sealing assertion: a late result against a terminal/sealed workflow does not
     reopen it (see scenario 7).

4. **Unanswered question then duplicate answer → no double unblocking; N superseded, N+1 unique (P4).**
   - Via `AgentStepQuestionService`: `ask` to put attempt N into `WAITING_HUMAN`; `answer` once →
     `supersededAttemptId == N`, attempt N+1 pending.
   - Submit the answer a second time → `QuestionAlreadyAnsweredException` (and/or
     `RevisionConflictException` on stale revision). Assert NO attempt N+2 was created (count
     attempts for the step) and attempt N stays terminal/immutable.

5. **Controller case renewal → controller/interlocutor stable, recovery packet bounded (P9).**
   - Model on `ControllerCaseServiceIntegrationTest`. Create workstream with `controllerAgentRef`,
     `startControllerCase`, seed enough projection state (>caps) to stress the builder, then
     `compactControllerCase`.
   - Assert: new case has the SAME `controllerAgentRef`, `sequence` incremented, previous archived;
     and `contextSummary.toByteArray(UTF_8).size <= ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES`
     (8192). Assert a too-long compaction reason → `INVALID_COMPACTION_REASON`.

6. **Incompatible plan change → rejected by governance gate, no silent rewrite (P8).**
   - Classify an incompatible proposal (e.g. `CONTRACT_OR_ORACLE_CHANGE_PROPOSAL` or
     `DEPENDENCY_CHANGE_PROPOSAL`). Assert `GovernanceGateEvaluator.recommendedVerdict(kind)` is NOT
     `AUTO_APPLIED`, and `assertDecisionAllowed(kind, AUTO_APPLIED)` throws (409
     `PLAN_CHANGE_GATE_REQUIRED`).
   - Through `PlanChangeProposalService.submit` + `decide`, assert the proposal is recorded as
     `GATE_REQUIRED`/`REQUIRES_NEW_DEFINITION`/`REJECTED` and that the underlying workflow definition
     is UNCHANGED after the rejected/gated attempt (re-read the definition/plan and compare — no
     silent rewrite).

7. **Terminal workflow instance → no implicit reopening via late messages/events (P10).**
   - Seed a workflow run and drive it to a terminal status (`COMPLETED`/`FAILED`/`CANCELLED`).
   - Attempt a transition/mutation and assert `WorkflowService` throws `WORKFLOW_SEALED` (409).
   - Attempt a late result/event (scenario 3 overlap) and assert it does not change the run status
     nor reopen the instance. `restore()` on a soft-deleted terminal run leaves it sealed.

8. **Workstream read-only tools answer correctly (P6) + opening a gate via command tool (P7).**
   - P6: seed state (workflows/attempts/oracles/interactions) and assert
     `WorkstreamProjectionService` / `DurableAgentAttemptService` return correct counts/summaries;
     repeated reads are stable (ETag/revision unchanged) and no mutation occurs — model on
     `WorkstreamProjectionIntegrationTest`.
   - P7: open a gate server-side (a `GATE_REQUIRED` plan-change decision via
     `PlanChangeProposalService`/`GovernanceGateEvaluator`, or a human-interaction/transition
     request via `WorkflowService`). Assert the gate state transitions and cannot be bypassed.

### Deferred / skipped smokes (document the missing dependency)

Add these as `@org.junit.jupiter.api.Disabled("...reason...")` test methods (JUnit 5) in the
resilience class, each with a comment explaining the missing hardware/infrastructure component so
the skip is a visible, intentional record (never a silent omission):

- **Writable worker in isolated `WorkUnitEnvironment`** — requires the real isolated
  WorkUnitEnvironment + writable worker (not yet built). `@Disabled`.
- **Real Factory oracle after worker** — requires the real post-worker Factory oracle execution
  path end-to-end. `@Disabled`.
- **Full BMAD/Forge workflow** — requires the full BMAD/Forge workflow runtime. `@Disabled`.

Each disabled test should still have a descriptive name and a KDoc stating the invariant it WILL
assert once the dependency exists, so the gap is self-documenting.

---

## Builder checklist / order of work

1. Create `factory-service/src/test/kotlin/io/whozoss/factory/resilience/Phase12ResilienceSmokesTest.kt`
   extending `Neo4jDomainIntegrationTest`. Copy the `FakeAdapter` + seed helpers from
   `DurableAgentOsBridgeIntegrationTest` / `BridgeRecoveryWorkerTest` /
   `ControllerCaseServiceIntegrationTest` / `WorkstreamProjectionIntegrationTest` as private helpers
   (do not share across modules; inline what you need).
2. Implement scenarios 1–8 as described, one `@Test` each (split a scenario into 2 tests where it
   has two distinct invariants, e.g. 3 and 7).
3. Add the three `@Disabled` deferred smokes with documented reasons.
4. If (and only if) a true on-disk reboot variant adds value beyond `recover()`, add
   `Phase12RestartDurabilitySmokesTest.kt` modelled on `AgentStepResultDurabilityTest` /
   `AgentStepQuestionDurabilityTest`.
5. Run `cd factory-service && ./gradlew test` (or `pnpm nx test factory-service`) until green.
6. If a smoke cannot assert its invariant because a production hook is missing, STOP and report the
   gap — do NOT weaken the assertion or patch `src/main`.
7. Commit: `test(factory-service): add Phase 12 resilience & invariant smoke suite`.

## Risks / notes

- Spring Test caches ONE context for all `Neo4jIntegrationTest` subclasses — keep the new class a
  plain subclass with no extra `@TestConfiguration`/`@MockBean` that would fork the context (slow).
- `@BeforeEach clearGraph()` runs via the base class; seed inside each test.
- Scenario 7 needs a terminal workflow — reuse the smoke workflow definition / helpers already
  present in `Neo4jIntegrationTest` / `WorkflowService` integration tests; do not add new
  definitions to production resources.
- Keep every assertion invariant-focused and explicit; a green test that doesn't assert the named
  invariant fails the acceptance criteria.
