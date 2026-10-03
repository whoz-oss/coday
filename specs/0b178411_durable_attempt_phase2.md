# Plan — Phase 2: AgentStepAttempt as the durable unit of agent execution

## Context & scope

The durable-execution attempt aggregate already exists and is mature. It lives in
`factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/` (note: the module
is `factory-service/`, **not** `agentos/factory-service/`). The relevant files are:

- `domain/AgentAttemptStatus.kt` — the state machine enum.
- `domain/DurableAgentAttempt.kt` — the domain record.
- `domain/DurableAgentAttemptExceptions.kt` — error codes + exceptions.
- `domain/DurableAgentAttemptDto.kt` — bounded public read model (`toDto()`).
- `persistence/DurableAgentAttemptNode.kt` — SDN `@Node("DurableAgentAttempt")`.
- `persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt` — atomic Cypher CAS statements.
- `persistence/DurableAgentAttemptRepository.kt` + `persistence/Neo4jDurableAgentAttemptRepository.kt`.
- `service/DurableAgentAttemptService.kt` — transactional façade.
- `service/BridgeRecoveryWorker.kt` — startup crash recovery sweep.
- `service/BridgeCancellationService.kt` — explicit business cancellation.

The orchestration caller is
`capability/CapabilityExecutionService.kt` (the three-phase bridge:
reserve → remote turn → finalize).

**This task is a verify-then-fill-gaps task, not a rewrite.** For every one of the 9
requirements below, the builder MUST first determine whether existing code already covers it,
and:

- If **already covered**, do NOT reimplement it. Instead add (or confirm) an explicit test
  that attests the behaviour, naming the requirement in the test's KDoc/`describedAs`.
- If **missing or only partially covered**, implement the minimum needed, with tests.

Commit per coherent step (Conventional Commits), one commit per requirement cluster.

### Hard constraints (do not violate)

- **Do NOT touch `agentos/`** (the Kotlin AgentOS core/SDK/plugins) nor
  `adapter/agentos/AgentOsExecutionAdapter` and its SSE client. Those are a separate concern.
- **Do NOT modify the result-path aggregate** (`AgentStepAttemptNode`,
  `AgentStepResult*`, outbox/idempotency/capability types and their tests). The durable
  attempt is a *sibling* aggregate; keep it that way.
- **Do NOT touch Flyway migrations or the release pipeline.** Persistence is Neo4j
  (schema-less; constraints are declared in `config/Neo4jSchemaInitializer.kt`).
- Kotlin style: no semicolons, 120-col, explicit return types on public funcs, KDoc on public
  types, kebab-case file names. Assert on `errorCode`, never on messages, in tests.
- Never write scratch output into the repo tree; use `/tmp`.

### How to run tests (debug only — the factory runs the suite after the build)

- Module: `pnpm nx test factory-service`
- Single: `cd factory-service && ./gradlew test --tests '*DurableAgentAttempt*'`
- Judge by exit status, not by scanning output for the word "error".

---

## Requirement-by-requirement gap assessment & work

### Req 1 — Append-only journal of the attempt + complete recovery

**Current state:** The attempt node (`DurableAgentAttemptNode`) is a *mutable snapshot* root:
every transition does an in-place `SET … a.revision = a.revision + 1`. There is **no
append-only journal of transitions** for this aggregate (contrast `DeliveryRecordNode`, which
is a true append-only journal keyed by a monotone `recordSequence`). Recovery itself is
complete and well-tested (`BridgeRecoveryWorker` + `BridgeRecoveryWorkerTest`,
`DurableAgentOsBridgeIntegrationTest`): reconcile → finalize / resume / re-drive, lease-fenced,
never fabricates success.

**Verdict: PARTIAL.** Recovery is covered (attest it with a test). The append-only transition
journal is the one genuinely missing piece.

**Work:**
1. Add an append-only journal of attempt lifecycle transitions. Model it on the delivery
   journal pattern already in the repo:
   - New `persistence/DurableAgentAttemptJournalNode.kt`:
     `@Node("DurableAgentAttemptJournal")`, `@Id val id` =
     `compositeId(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId, sequence)`,
     plus plain fields: `attemptId`, `sequence: Long` (monotone, `MAX(sequence)+1` per attempt),
     `fromStatus: String?`, `toStatus: String`, `ownerToken: String?`, `failureCode: String?`,
     `resultEvidenceId: String?`, `lastObservedEventId: String?`, `recordedAt: Instant`,
     `revisionAfter: Int`. Mirror the KDoc style of `DeliveryRecordNode`.
   - New Spring-Data interface
     `persistence/SpringDataNeo4jDurableAgentAttemptJournalRepository.kt` with:
     an `append` `@Query` that computes `MAX(sequence)+1` for the attempt (copy the delivery
     journal's monotone-sequence Cypher), and a `findByAttempt(...)` query ordered by
     `sequence ASC`.
   - Extend the port `DurableAgentAttemptRepository` and
     `Neo4jDurableAgentAttemptRepository` so that **every successful state change**
     (`register` → initial `pending` entry, `claim`, `transition`, `finalize`, `cancel`)
     also appends a journal row **inside the same transaction** as the CAS. Append only when
     the CAS matched (count > 0) — a fenced/conflicted no-op writes no journal entry.
   - Add a read method `journal(scope, …, attemptId): List<DurableAgentAttemptJournalEntry>`
     on the port + service (map node → a small domain/DTO record;
     `@Transactional(readOnly = true)`).
   - Register a uniqueness constraint for the new node label in
     `config/Neo4jSchemaInitializer.kt` (copy an existing `CREATE CONSTRAINT … IF NOT EXISTS`
     block; e.g. `durable_agent_attempt_journal_id_unique`). Also add the matching index if the
     neighbours do.
2. Keep it minimal and additive — the journal is a side-record; the mutable node stays the
   authoritative current state. Do not change existing read paths.

**Tests (new, under `src/test/.../agentattempt/`):**
- `DurableAgentAttemptJournalTest : Neo4jDomainIntegrationTest`:
  register → claim → starting → running → succeeded, then assert `journal(...)` returns the
  ordered entries `pending, claiming, starting, running, succeeded` with monotone `sequence`,
  correct `fromStatus/toStatus`, and that `revisionAfter` is strictly increasing.
  Assert a **fenced** finalize (divergent owner) appends **no** journal entry.
- Attestation test for recovery (Req 1 "recovery complets"): confirm the existing
  `BridgeRecoveryWorkerTest` already covers reconcile→finalize, resume-without-second-turn,
  live-lease-not-stolen, re-drive-only-when-never-accepted, waiting-human resume. If a gap
  exists, add a focused test; otherwise add a one-line KDoc note in the journal test file
  pointing to `BridgeRecoveryWorkerTest` as the recovery attestation and do not duplicate.

### Req 2 — Imposed transitions + rejection of illegal transitions

**Current state:** Fully covered. `AgentAttemptStatus.ALLOWED_TRANSITIONS` encodes exactly
`pending→{claiming,interrupted}`, `claiming→{starting,failed,indeterminate,interrupted}`,
`starting→{running,failed,indeterminate,interrupted}`,
`running→{waiting_human,succeeded,failed,indeterminate,interrupted}`,
`waiting_human→{running,succeeded,failed,indeterminate,interrupted}`, terminals → ∅.
`Neo4jDurableAgentAttemptRepository.assertTransitionAllowed` throws
`InvalidAttemptTransitionException` (`ATTEMPT_INVALID_TRANSITION`).
Note the prompt lists `pending→starting→running` directly, but the real machine inserts a
`claiming` step between `pending` and `starting`; that is the current, correct design and the
tests must reflect it (do not "fix" the machine to match the prompt's shorthand).

`AgentAttemptStateMachineTest` already asserts the allowed set, the succeeded-source
invariant, terminal immutability and db round-trip.

**Verdict: COVERED.** Prove it.

**Work:** none in production code. In tests, add an **explicit illegal-transition rejection**
assertion if not already present end-to-end: in `AgentAttemptStateMachineTest`, add a test
`illegal transitions are rejected by canTransitionTo` iterating every `(from,to)` pair NOT in
`ALLOWED_TRANSITIONS` and asserting `canTransitionTo == false`. (The positive direction is
already tested; this makes the rejection explicit.) No new service-level test needed —
`DurableAgentAttemptFencingTest` already asserts `ATTEMPT_INVALID_TRANSITION` at the
persistence boundary for `pending→succeeded` and `starting→succeeded`.

### Req 3 — Idempotent reservation of the attempt BEFORE AgentOS case creation

**Current state:** Covered. `CapabilityExecutionService.resolveAgentViaAdapter` runs
**Phase 1** (`reserveAgentAttempt`: `register` + atomic `claim`, each in its own short
transaction, serialised by a process-local reservation lock) **before Phase 2**
(`executeRemoteTurn` → `adapter.createOrRecoverExecution`). `register` is a `MERGE`
(idempotent by `attemptId`); a re-register returns the live node unchanged; a competing live
lease yields `conflicted` and no turn starts. `DurableAgentOsBridgeIntegrationTest`
("two concurrent executions yield exactly one owning attempt and one turn") and
`DurableAgentAttemptClaimConcurrencyTest` attest the idempotent/atomic claim.

**Verdict: COVERED.** Prove it with an explicit ordering test.

**Work:** none in production code. Add an attestation test in
`DurableAgentOsBridgeIntegrationTest` (or a focused new test) asserting that **at the moment
`adapter.createOrRecoverExecution` is first invoked, the durable attempt already exists** and
is in a reserved (`claiming`/`starting`) state. Implement by overriding `createOrRecoverExecution`
in the fake adapter to read `durableAgentAttemptService.find(...)` and assert it is non-null
and non-`pending`. This nails "reservation BEFORE case creation" explicitly.

### Req 4 — Persist the caseId BEFORE sending the useful work

**Current state:** Covered by design. The `caseId` is **deterministic**
(`CapabilityExecutionService.stableCaseId(workflowId, stepId)` =
`UUID.nameUUIDFromBytes("$workflowId#$stepId")`) and is persisted on the node at `register`
time (`DurableAgentAttemptNode.caseId`), i.e. in Phase 1 — strictly before Phase 2's
`startTurn`. The attempt is also marked `starting` **before** `adapter.startTurn` is dispatched,
and recovery relies on that persisted `caseId` to reconcile/recover
(`DurableAgentOsBridgeIntegrationTest` recovery + idempotence cases).

**Verdict: COVERED.** Prove it.

**Work:** none in production code. Add an attestation test asserting that, at the instant the
fake adapter's `startTurn` is invoked, `durableAgentAttemptService.find(...).caseId` is already
the stable case id **and** the status is `starting` (persisted before dispatch). This directly
attests "caseId persisted before useful work is sent".

### Req 5 — Factory timestamps, failure codes, structured result references

**Current state:** Covered. `DurableAgentAttempt`/`DurableAgentAttemptNode` carry
`createdAt`, `startedAt`, `updatedAt`, `completedAt` (Factory-side `Instant`s, set by the
CAS statements with the service-supplied `now`), `failureCode: String?`, and
`resultEvidenceId: String?` (the structured-result reference → the `agent-result` evidence id
persisted by `finalizeAgentAttempt`). `DurableAgentAttemptFencingTest` asserts `completedAt`,
`resultEvidenceId`, `lastObservedEventId` and `failureCode` ("timeout") on finalize.

**Verdict: COVERED.** Prove it (likely already proven).

**Work:** none in production code. Confirm the existing assertions in
`DurableAgentAttemptFencingTest` cover: `createdAt` set at register, `startedAt` set at claim,
`completedAt` set at finalize, `failureCode` persisted on a failed/indeterminate finalize, and
`resultEvidenceId` persisted on success. If any of these specific fields is not asserted,
extend the existing test with the missing assertion (do not create a new test file for this).

### Req 6 — Timeout / unknown state ⇒ indeterminate, never succeeded

**Current state:** Covered at two layers. (a) The state machine makes `SUCCEEDED` reachable
only from `RUNNING`/`WAITING_HUMAN` (`AgentAttemptStateMachineTest`), so a timed-out
`pending`/`claiming`/`starting` attempt physically cannot finalize to `succeeded`
(`DurableAgentAttemptFencingTest`: `pending→succeeded` and `starting→succeeded` both throw
`ATTEMPT_INVALID_TRANSITION`; the timeout path finalizes `INDETERMINATE` with
`failureCode="timeout"`). (b) The adapter/observation layer maps observation errors/timeouts to
`AgentOsExecutionVerdict.Indeterminate` and `finalizeAgentAttempt` maps that to
`AgentAttemptStatus.INDETERMINATE` with `failureCode="AGENT_INDETERMINATE"`; the SSE
`VerdictDeriver` never derives `Succeeded` from free text.

**Verdict: COVERED.** Prove it.

**Work:** none in production code. The attestation already exists in
`DurableAgentAttemptFencingTest` ("an incomplete or never-run attempt can never finalize as
succeeded"). Add a single explicit test in that file (if not present) that drives the
*observation-timeout* path: call `service.finalize(..., target = INDETERMINATE,
failureCode = "timeout")` from a `running` attempt and assert status `INDETERMINATE`, and
assert that calling `finalize(..., SUCCEEDED)` from a `claiming`/`starting` state throws. Name
the test for Req 6.

### Req 7 — Retry ⇒ new attempt (attemptNumber incremented), never reactivation of a terminal attempt (immutable)

**Current state: THE REAL GAP.** The attempt id is **deterministic and fixed** per step:
`stableAttemptId(workflowId, stepId) = "$workflowId#$stepId"`, with `attemptNumber` **hard-coded
to `1`** at register (`CapabilityExecutionService` line ~543 and every test fixture). Because
the id does not include the attempt number, a retried run resolves to the **same** attempt
node. Terminal immutability holds (terminal states have no outgoing transitions, and
`reserveAgentAttempt` replays a terminal attempt via `terminalAgentExecution` without
re-driving AgentOS), so a terminal attempt is never *reactivated*. But there is **no path that
creates attempt N+1** — the "retry ⇒ new attempt with incremented attemptNumber" half of the
requirement is unimplemented. Retry today (`WorkflowService.openRetry` + `replyInteraction`
retry branch) operates at the workflow-step level and re-opens a *blocked* step, but the agent
attempt id does not roll to a new number.

**Verdict: PARTIAL — immutability covered, attemptNumber-increment-on-retry missing.**

**Work (minimal, additive, must not break the stable-id replay/idempotence invariants):**
1. Introduce an attempt-number-aware identity **without breaking existing idempotent replay**.
   The safest minimal design:
   - Add a repository/service method
     `nextAttemptNumber(scope, namespaceId, workflowId, stepId): Int` returning
     `MAX(attemptNumber)+1` across attempts of that `(namespaceId, workflowId, stepId)`
     (default `1` when none). Add the backing Cypher in
     `SpringDataNeo4jDurableAgentAttemptRepository` (there is already `findByWorkflowId`;
     add a `findByStep` or a dedicated `maxAttemptNumber` aggregate query).
   - Add a deterministic **retry attempt id** helper on `CapabilityExecutionService`, e.g.
     `fun retryAttemptId(workflowId, stepId, attemptNumber) = "$workflowId#$stepId#$attemptNumber"`,
     keeping `stableAttemptId(workflowId, stepId)` (== `#1` semantics) as the first attempt so
     existing replay/idempotence behaviour and all current tests remain green.
   - When a step is explicitly retried (the `openRetry`/`replyInteraction` retry path, or a
     dedicated service entry point the builder adds for the DAG re-run of a blocked step),
     allocate `attemptNumber = nextAttemptNumber(...)` and register a **brand-new**
     `DurableAgentAttempt` with that number and the retry attempt id. The prior terminal
     attempt is left untouched (immutable record of the earlier try).
   - Guard: a terminal attempt must never be re-registered/overwritten. `register` is a
     `MERGE … ON CREATE`, so a terminal node is already not clobbered; add a defensive check
     in the retry path that the new attempt id is genuinely new (never equal to an existing
     terminal attempt's id) and that `attemptNumber` is strictly greater than any prior.
2. Scope decision: wire the new attempt only into the **blocked-step retry** path that already
   exists. Keep the change surgical — if wiring into `WorkflowService.openRetry`/sequencer
   re-run is larger than a focused change, implement the domain/service primitives
   (`nextAttemptNumber`, `retryAttemptId`, new-attempt registration) and a direct service-level
   test, and document in the commit body exactly where the caller hook is (or is deliberately
   deferred). The acceptance criterion is provable at the `DurableAgentAttemptService` level
   without needing the full HTTP retry flow.

**Tests (new):**
- `DurableAgentAttemptRetryTest : Neo4jDomainIntegrationTest`:
  1. Register + claim + run + **finalize `FAILED`** attempt #1.
  2. Attempt a `claim`/`transition`/`finalize` on the terminal attempt #1 → assert it is
     rejected (`ATTEMPT_INVALID_TRANSITION` or lease fencing) and the node stays `failed`
     (immutability).
  3. Allocate `nextAttemptNumber` (expect `2`) and `register` a new attempt #2 with the retry
     id; assert it is a **distinct node** (`attemptNumber == 2`, different id, status
     `pending`), that attempt #1 is still present and `failed`, and that the journal/history of
     #1 is unchanged.
  4. Assert `findByWorkflow` now returns both attempts.
- If a controller/service retry hook is wired, add a thin integration test asserting the retry
  produces attempt #2; otherwise document the deferral in the commit body.

### Req 8 — Link attempt → environmentRef and expectedRevision

**Current state: PARTIAL / MISSING on `DurableAgentAttempt`.** `expectedRevision` exists for
cancellation only (`cancel`/`requestCancel`, revision-fenced). There is **no `environmentRef`
field** on `DurableAgentAttempt`/`DurableAgentAttemptNode`. The work-environment aggregate
(`environment/domain/WorkEnvironment`, addressable by `workflowId` via
`findLatestByWorkflowId`) exists independently, but the attempt carries no reference to the
environment it ran against, and no `expectedRevision` of that environment is captured on the
attempt.

**Verdict: MISSING — implement the link.**

**Work (additive, nullable so legacy nodes stay valid):**
1. Add two nullable fields to `DurableAgentAttempt` and `DurableAgentAttemptNode`:
   `environmentRef: String?` (the `environmentId` of the `WorkEnvironment` the attempt ran
   against) and `expectedEnvironmentRevision: Int?` (the environment revision the attempt was
   bound to — i.e. the optimistic-lock revision captured at reservation time). Thread them
   through `toDomain()`/`fromDomain()` and the `register` `MERGE` `ON CREATE SET` (so they are
   set at reservation and never clobbered afterwards). Default `null` everywhere.
2. Populate them at reservation in `CapabilityExecutionService.reserveAgentAttempt`: resolve
   the current environment via the existing `WorkEnvironmentRepository.findLatestByWorkflowId`
   (inject the repo as an **optional/nullable** constructor dependency so existing unit-test
   constructions of `CapabilityExecutionService` keep compiling — follow the existing
   nullable-dependency pattern used for `agentStepResultService`), and set
   `environmentRef = env?.environmentId`, `expectedEnvironmentRevision = env?.revision`.
   When no environment exists, leave both `null` (no behaviour change).
3. Expose `environmentRef` (and optionally `expectedEnvironmentRevision`) on
   `DurableAgentAttemptDto` + `toDto()` so the operator-visible projection carries the link.
   Do **not** expose secrets; these two fields are safe operator metadata.

**Tests (new / extended):**
- Extend `DurableAgentAttemptFencingTest` or add
  `DurableAgentAttemptEnvironmentLinkTest : Neo4jDomainIntegrationTest`:
  register an attempt with `environmentRef`/`expectedEnvironmentRevision` set, claim, finalize,
  and assert both survive the full lifecycle unchanged and appear on `find(...)` and `toDto()`.
- A `CapabilityExecutionService`-level test (reuse `DurableAgentOsBridgeIntegrationTest`
  harness): with a provisioned `WorkEnvironment` for the workflow, assert the reserved attempt
  has `environmentRef == env.environmentId` and `expectedEnvironmentRevision == env.revision`.

### Req 9 — Projection of the attempt independent of the live runtime

**Current state:** Covered. `DurableAgentAttemptDto` + `DurableAgentAttempt.toDto()` is a
bounded, secret-free read model (explicitly excludes `ownerToken`, `capabilityToken`,
`commandId`, `brief`, `leaseExpiresAt`, `lastObservedEventId`, `turnCorrelation`). It is read
purely from persistence via `WorkflowController.listAttempts` →
`DurableAgentAttemptService.findByWorkflow` (`@Transactional(readOnly = true)`) — no AgentOS/SSE
call, no live runtime dependency.

**Verdict: COVERED.** Prove it (there is currently **no** test referencing `toDto`).

**Work:** none in production code (beyond the Req 8 additions to the DTO).

**Tests (new):**
- `DurableAgentAttemptProjectionTest`:
  - Pure unit test of `toDto()` asserting the secret fields are **absent** from the DTO type
    (compile-time) and that the lifecycle/identity fields map correctly, including the Req 8
    `environmentRef`.
  - A `Neo4jDomainIntegrationTest`-based test that registers/claims/finalizes an attempt and
    reads it back through `service.findByWorkflow(...).map { it.toDto() }` **without any
    adapter/SSE interaction**, asserting the projection reflects the persisted terminal state.
    This attests "projection independent of the live runtime".

---

## Commit plan (Conventional Commits, one coherent step each)

1. `test(agentattempt): attest imposed transitions and illegal-transition rejection` — Req 2
   (+ Req 6 explicit timeout→indeterminate assertion). Tests only.
2. `feat(agentattempt): append-only transition journal with recovery attestation` — Req 1
   (journal node + repo + schema constraint + wiring + tests).
3. `test(agentattempt): attest attempt reservation and caseId persisted before the turn` —
   Reqs 3 & 4 (ordering attestation tests; no prod change).
4. `test(agentattempt): attest Factory timestamps, failure codes and result refs` — Req 5
   (extend fencing test if needed).
5. `feat(agentattempt): new attempt per retry with incremented attemptNumber` — Req 7
   (nextAttemptNumber + retryAttemptId + new-attempt registration + immutability tests).
6. `feat(agentattempt): link attempt to environmentRef and expected environment revision` —
   Req 8 (nullable fields + reservation wiring + DTO + tests).
7. `test(agentattempt): attest runtime-independent attempt projection` — Req 9
   (`toDto` projection tests).

Order 1→7 keeps each commit green. If a requirement proves already fully attested by an
existing test, fold its "attestation" commit into a smaller `test:` commit that adds the single
missing assertion, and say so in the commit body — do not reimplement covered behaviour.

## Verification

- `pnpm nx test factory-service` green (or `cd factory-service && ./gradlew test`).
- `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"` clean.
- `pnpm nx affected -t build --base="$(cat /work/data/baseline)"` passes.
- Judge every command by exit status.

Acceptance mapping:
- Req 1 → `DurableAgentAttemptJournalTest` + `BridgeRecoveryWorkerTest` (recovery attestation).
- Req 2 → `AgentAttemptStateMachineTest` (allowed + rejected) + `DurableAgentAttemptFencingTest`.
- Req 3 → reservation-before-case ordering test in the bridge integration test.
- Req 4 → caseId-persisted-before-startTurn ordering test.
- Req 5 → `DurableAgentAttemptFencingTest` timestamp/failureCode/resultEvidenceId assertions.
- Req 6 → `AgentAttemptStateMachineTest` + `DurableAgentAttemptFencingTest` indeterminate path.
- Req 7 → `DurableAgentAttemptRetryTest`.
- Req 8 → environment-link tests + bridge reservation test.
- Req 9 → `DurableAgentAttemptProjectionTest`.
