# Phase 12 — Resilience & Invariant End-to-End Smokes

## What changed

Two new files, both additive (no production code touched):

1. **`factory-service/src/test/kotlin/io/whozoss/factory/resilience/Phase12ResilienceSmokesTest.kt`**
   (~1100 lines) — the smoke suite itself, in a new `io.whozoss.factory.resilience` test package.
2. **`specs/5196d030_phase12_resilience_smokes.md`** — the working spec for the phase: recon
   notes on the exact harnesses/services used, scenario-by-scenario test specification, and the
   hard constraints (test-only, no migrations, no pipeline changes).

## What the suite does

`Phase12ResilienceSmokesTest` extends `Neo4jDomainIntegrationTest` (the shared embedded-Neo4j
Spring Boot harness) and drives the **real service beans** — `DurableAgentAttemptService`,
`AgentStepResultService`, `AgentStepQuestionService`, `WorkflowService`, `ControllerCaseService`,
`WorkstreamProjectionService`, `PlanChangeProposalService` — plus a stateful in-test
`FakeAdapter : AgentOsExecutionAdapter` for the AgentOS boundary. It is a plain subclass with no
extra `@TestConfiguration`/`@MockBean`, so it reuses the single cached Spring test context.

Each test asserts **one named safety invariant** under a simulated failure/stress scenario:

| # | Scenario | Invariant asserted |
|---|----------|-------------------|
| 1 | Factory restart mid-attempt (P2/P3) — seed running attempts with `leaseTtlMs = 0`, then run `BridgeRecoveryWorker(...).recover()` | Recovery *explains* state (`INDETERMINATE` / `WAITING_HUMAN`), never infers `SUCCEEDED`; no turn re-sent (`startTurns` empty) |
| 2 | AgentOS unreachable (P3) — `reconcile` throws "Connection refused" | Attempt stays non-terminal and is skipped by the sweep; `VerdictDeriver` unit assertions: non-quiescent events → `null`, IDLE-without-result → `Indeterminate(AGENT_NO_STRUCTURED_RESULT)`, never `Succeeded` |
| 3 | Late + duplicate step-result submission (P1/P10) | Identical replay → `idempotent == true`, same `resultId`/`resultHash`, exactly one result row and one outbox event; divergent replay → `ResultSemanticCollisionException` / `IdempotencyKeyCollisionException`; verdict byte-for-byte intact, attempt stays `"completed"` |
| 4 | Unanswered question then duplicate answer (P4) — plus a companion stale-revision test | First answer supersedes attempt N and registers a unique pending N+1; duplicate answer → `QuestionAlreadyAnsweredException`, no N+2 ever created; stale revision → `RevisionConflictException` mutating nothing |
| 5 | Controller case renewal (P9) — projection stressed past every per-section cap (15 workflows, 12 attempts) | `compactControllerCase` keeps the same `controllerAgentRef`/workstream, increments sequence, and the `contextSummary` stays ≤ `ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES`; over-long compaction reason → `INVALID_COMPACTION_REASON` with no state change |
| 6 | Incompatible plan change (P8) — `CONTRACT_OR_ORACLE` proposal | Classifier recommends `REQUIRES_NEW_DEFINITION` (never `AUTO_APPLIED`); an `AUTO_APPLIED` decide is rejected with 409 `PLAN_CHANGE_GATE_REQUIRED`; the explicit `REJECTED` path is immutable and the original proposal payload is preserved byte-for-byte — no silent rewrite |
| 7 | Terminal workflow instance (P10) — drive a human-gate workflow to `completed` | Late `transition`, `codeTransition`, and a late `replyInteraction` all throw `WORKFLOW_SEALED`; instance revision and run status unchanged |
| 8 | Workstream read-only answers (P6) + gate via command (P7) | Aggregated projection returns correct counts (workflows, steps, attempts, human actions), repeated reads keep the same ETag/revision and mutate nothing; a `GATE_REQUIRED` plan-change decision is durably recorded and cannot be bypassed by a later `AUTO_APPLIED`; a human-gate `openInteraction` persists a `waiting` interaction |

## Deferred smokes

Three scenarios whose dependencies are not built yet are present as `@Disabled` tests with a KDoc
stating the invariant they *will* assert and the missing dependency:

- writable worker in an isolated `WorkUnitEnvironment` (no isolated sandbox runtime exists yet),
- real Factory oracle after the worker (only the smoke oracle catalogue exists in tests),
- full BMAD/Forge workflow (agents, definitions and infrastructure not yet built).

## How to verify

```
cd factory-service && ./gradlew test
# or
pnpm nx test factory-service
```

The eight active smokes run under the shared embedded-Neo4j context; the three `@Disabled` smokes
report as skipped with their dependency reasons. The change is test-only: no `src/main` code, no
migrations, no release-pipeline files were touched.

## Notes for the next engineer

- To add a new resilience smoke, extend `Phase12ResilienceSmokesTest` (or a sibling class in the
  same package) as a plain `Neo4jDomainIntegrationTest` subclass — do not add
  `@TestConfiguration`/`@MockBean`, or you fork the cached Spring context and slow every
  integration test.
- The `FakeAdapter` inside the test class is the pattern for simulating the AgentOS boundary:
  reconcile/observe verdicts are lambdas supplied per test, and a crashed worker is simulated by
  seeding durable attempts with an already-expired lease (`leaseTtlMs = 0`).
- The spec file `specs/5196d030_phase12_resilience_smokes.md` documents which production services
  back each invariant (e.g. `GovernanceGateEvaluator` for P8, `assertNotSealed` for P10) and is
  the place to check before extending the suite.
