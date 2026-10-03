# Plan — Phase 10: Governance of Terminal States, Sealing, and Late-Result Policy

## Context & scope

This phase hardens and **attests** the immutability (sealing) of terminal states across the two
Factory control-plane aggregates that own execution lifecycle, and documents how AgentOS runtime
states relate to that sealing. It is a **verify-then-fill-gaps** task: most of the sealing already
exists and is mature — the builder must first confirm existing coverage with an explicit,
requirement-named test, and only implement where a real gap exists.

### Target packages (edit ONLY inside these two trees)

- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/`
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/`

Tests go under the mirror trees:
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/`
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/`

### Hard constraints (do not violate)

- **Do NOT touch** `agentos/tools`, `agentos/` (SDK/core/plugins), `factory-service/workstream/`,
  `factory-service/.../adapter/agentos/` (`CaseStatus`, `VerdictDeriver`, `CaseEventView`,
  `AgentOsExecutionVerdict`, `AgentOsExecutionAdapter` and its SSE client), DB migrations, Flyway,
  or the release pipeline. Requirement 3 is satisfied **entirely inside `agentattempt/`** by
  documentation + a read-model classification — it never edits the AgentOS adapter.
- Persistence is Neo4j (schema-less). Node constraints live in
  `config/Neo4jSchemaInitializer.kt` (**out of the target packages — do not touch**; no new
  constraints are required by this phase).
- Kotlin style: no semicolons, 120-col lines, explicit return types on public functions, KDoc on
  public types, kebab-case file names. Prefer functional, single-responsibility code.
- **Tests assert on `errorCode`, never on messages.**
- **Strict build-gate claim**: any file path a test (or its KDoc/`describedAs`) names MUST be a
  real, exact path in the repo. Do not invent paths; copy them from this plan or the tree.
- Never write scratch output into the repo tree; use `/tmp`.
- Conventional Commits, one commit per requirement cluster.

### How to run tests (debug only — the factory runs the suite after the build)

- Module: `pnpm nx test factory-service`
- Narrowed: `cd factory-service && ./gradlew test --tests '*AgentAttempt*' --tests '*Workflow*'`
- Judge by exit status, not by scanning output for the word "error".

---

## Current state (established by reconnaissance — do not re-derive from scratch)

**Durable attempt aggregate** (`agentattempt/`) is already strongly sealed:

- `domain/AgentAttemptStatus.kt` — terminal states `succeeded|failed|indeterminate|interrupted|superseded`
  have `terminal = true` and **empty** `ALLOWED_TRANSITIONS` target sets; `isSuccess` is true only for
  `SUCCEEDED`. `canTransitionTo` rejects every outgoing transition from a terminal state.
- `persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt` — `transition`, `finalize`, `cancel`,
  `supersede`, `claim` all carry `WHERE ... AND NOT a.status IN ['succeeded','failed','indeterminate','interrupted','superseded']`
  in the Cypher CAS, so a terminal node matches nothing (count 0).
- `persistence/Neo4jDurableAgentAttemptRepository.kt` — `assertNotFenced` + `assertTransitionAllowed`
  + `disambiguateFailedCas` reject late/stale mutations with `AttemptLeaseFencingException` /
  `InvalidAttemptTransitionException`; idempotent terminal replays return the existing record.
- `service/DurableAgentAttemptService.kt` — `registerRetry` forbids reusing a prior `attemptId`
  (brand-new attempt `N+1`); `supersede` keeps attempt `N` immutable.
- `service/BridgeCancellationService.kt` — already rejects cancelling a terminal attempt
  (`InvalidAttemptTransitionException`) and is idempotent on `interrupted`.

**Result-path attempt** (`agentattempt/`, the `AgentStepAttempt` sibling root) has ONE real gap:

- `persistence/SpringDataNeo4jAgentStepAttemptRepository.kt::terminalize` is an **unguarded**
  `SET a.status = $status` — it does NOT fence against an already-terminal attempt. The service
  layer protects most paths (single-use capability, result-row semantic-collision check in
  `Neo4jAgentStepResultRepository.submit`, and the startup reconcile in
  `AgentStepResultService.reconcileOnStartup` which re-checks `TERMINAL_ATTEMPT_STATUSES` before
  terminalizing), but the storage primitive itself can still overwrite a sealed verdict. This is
  the invariant "a late result NEVER changes an already sealed verdict" expressed at the wrong
  layer. → **Fill this gap** (Requirement 1).

**Workflow aggregate** (`workflow/`):

- `domain/WorkflowModels.kt::WorkflowStatuses.TRANSITIONS` already gives `completed`, `failed`,
  `cancelled` **empty** outgoing sets, so a *step-level* transition out of a terminal step is
  rejected (`ILLEGAL_TRANSITION`) by `WorkflowTransitionPolicy.evaluateTransition`.
- But there is **no explicit guard on the overall workflow-instance run status**: `WorkflowService`'s
  `transition` / `codeTransition` / human-resolution path only call `activeInstance(...)`, which
  checks the **lifecycle** state (`active` vs `removed`/`purged`) — a different axis from the **run**
  status (`completed`/`failed`/`cancelled`). A request against a workflow whose overall run status is
  terminal is currently rejected only indirectly (via the step state machine). → **Add an explicit,
  dedicated top-level seal** (Requirement 2) so a sealed workflow is rejected with its own machine
  code regardless of per-step bookkeeping.
- `restore()` reopens `removed → active` — this is **lifecycle** (soft-delete recovery), NOT a run-status
  reopen; it must stay, but must be documented as orthogonal and must not resurrect a terminal run.
- Successor/relink mechanism already exists: `WorkflowStartCommand.relations` + `independentWorkflowRelations`
  (`rootWorkflowId`) in `domain/WorkflowInstance.kt`. → Extend with an explicit **linked-successor**
  helper + docs (Requirement 2).

---

## Requirement 1 — Attempt sealing, immutability & late-result policy

**Invariants:** a terminal attempt is immutable; a late capability redemption / late result submission
targeting a sealed attempt is rejected or logged as a late result **without changing the sealed
verdict/status**; late capability tokens/actions cannot alter sealed attempts.

### 1a. Fill gap — fence the result-path `terminalize` primitive

File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jAgentStepAttemptRepository.kt`

- Change the `terminalize` Cypher to add a terminal guard so a sealed attempt matches nothing:

  ```
  MATCH (a:AgentStepAttempt {id: $id})
  WHERE NOT a.status IN ['completed', 'failed']
  SET a.status = $status,
      a.revision = a.revision + 1,
      a.updatedAt = $updatedAt
  RETURN count(a) AS updated
  ```

- Update the KDoc to state the method now returns 0 when the attempt is already sealed terminal
  (`completed`/`failed`), making the primitive itself enforce "a late result never changes a sealed
  verdict". `AgentStepAttemptStatus` for this sibling root uses the string vocabulary
  `running | completed | failed` (see `AgentStepResultService` companion constants
  `ATTEMPT_COMPLETED`/`ATTEMPT_FAILED`); keep those exact strings.

File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jAgentStepAttemptRepository.kt`

- `terminalize(...)` already returns the matched count. No signature change needed; confirm it
  propagates the 0/1 result.

### 1b. Fill gap — late-result handling in the submission transaction

File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jAgentStepResultRepository.kt`
(method `submit`)

- Today `submit` calls `attempts.terminalize(...)` and ignores the return. With 1a the call now
  returns 0 when the attempt is already sealed. Capture the count and, when it is 0 (sealed), **do not
  throw and do not rewrite the verdict** — the authoritative result row and the terminal attempt are
  already consistent (the first submission sealed them). Log at `info`/`warn` level a stable
  "late result ignored for sealed attempt" message that includes `attemptId`/`workflowId`/`stepId`
  (log text only — tests must not assert on it). The existing result-row collision guard
  (`payloadType(existing.payload) == SUBMITTED_TYPE` → `Replayed` on identical hash,
  `ResultSemanticCollisionException` on divergent hash) stays the authoritative late-submission
  policy for the result row itself; keep it unchanged.
- Net effect: a late redemption against a sealed attempt either replays the identical result
  (idempotent) or is rejected as a semantic collision — it can never flip the sealed verdict.

### 1c. Attest the durable-attempt sealing (already covered — add explicit attestation)

New test file: `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/DurableAttemptSealingTest.kt`

Using the existing in-memory/embedded test harness pattern from
`DurableAgentAttemptFencingTest.kt` and `DurableAgentAttemptRetryTest.kt`, add tests whose KDoc /
`describedAs` names the Phase 10 invariant. Reuse real paths only. Cover:

- Finalizing a durable attempt to a terminal status, then a **second** `finalize`/`transition` to any
  status is rejected (`InvalidAttemptTransitionException` for a divergent target by the same owner,
  or `AttemptLeaseFencingException` for a diverged token) — never silently mutates. Idempotent replay
  of the **same** terminal target by the **same** owner returns the existing record unchanged.
- `cancel` and `supersede` against an already-terminal attempt throw `InvalidAttemptTransitionException`
  (idempotent only on their own terminal status).
- `registerRetry` with an already-used `attemptId` throws `IdempotencyKeyCollisionException`; a retry
  always registers a brand-new `attemptId` with `attemptNumber = N+1` while attempt `N` stays
  byte-for-byte unchanged (read it back and compare status/revision).

### 1d. Attest the result-path sealing / late-result policy

New test file: `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultSealingTest.kt`
(follow `AgentStepResultServiceIntegrationTest.kt` for harness/wiring)

- Issue a capability, submit a result (seals the attempt as `completed`/`failed`).
- Submitting the **identical** result again (same capability, same body) → idempotent `Replayed`,
  attempt status and revision unchanged.
- Submitting a **divergent** result → `ResultSemanticCollisionException` (`RESULT_SEMANTIC_COLLISION`),
  attempt verdict unchanged.
- Directly invoking `terminalize` on an already-terminal `AgentStepAttempt` returns `0` and leaves the
  status/revision untouched (attests 1a at the repository level).
- An expired capability → `ResultCapabilityExpiredException` (`RESULT_CAPABILITY_EXPIRED`); the sealed
  attempt is never altered.

Commit: `feat(agentattempt): seal result-path attempt terminalize and attest attempt immutability`

---

## Requirement 2 — WorkflowInstance terminal governance

**Invariants:** `completed`, `failed`, `cancelled` are final and sealed; reopening a terminal
workflow instance is strictly forbidden (reject the transition / throw on mutation); resuming or
re-running a terminal requirement happens via a **new** workflow linked to the parent/previous one —
never by reopening a sealed workflow.

### 2a. Add a public terminal predicate to the status vocabulary

File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`
(object `WorkflowStatuses`)

- Add a public `TERMINAL` set `= setOf(COMPLETED, FAILED, CANCELLED)` and `fun isTerminal(status: String?): Boolean`.
  (The existing `SessionSequencer.TERMINAL` is private and identical in intent — leave it, but the new
  public predicate is the single source for the workflow seal guard; optionally have `SessionSequencer`
  reference the public set in a follow-up, not required here.)

### 2b. Add a dedicated "workflow sealed" error code

File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowExceptions.kt`

- Add `const val WORKFLOW_SEALED = "WORKFLOW_SEALED"` to `WorkflowErrorCodes`.
- Map it to HTTP **409** in `workflowStatusCode(...)` (it falls into the default 409 branch — add it
  explicitly to the `else -> 409` documentation or leave to default; prefer an explicit entry comment).

### 2c. Enforce the seal in the service transition paths

File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`

- Add a private helper `assertNotSealed(instance: WorkflowInstanceRecord)` that reads the overall run
  status from `instance.instance["status"]` (the governed instance carries `status`) and throws
  `workflowException(WorkflowErrorCodes.WORKFLOW_SEALED, ...)` when `WorkflowStatuses.isTerminal(status)`.
- Call `assertNotSealed(instance)` immediately after `activeInstance(...)` in:
  - `fun transition(...)` (line ~430),
  - `fun codeTransition(...)` (line ~478),
  - the human-resolution transition path around line ~850 (the third `applyTransition` call site).
- Rationale: the step-level state machine already blocks reopening a terminal **step**; this adds an
  explicit, uniform, top-level rejection with its own machine code for a terminal **instance**, so the
  seal is enforced even if a future definition change made per-step bookkeeping ambiguous.
- **Do not** change `restore()`/`remove()`/`purge()`: those operate on the orthogonal **lifecycle**
  axis. Add KDoc to `restore()` clarifying it recovers a soft-deleted (`removed`) projection and never
  reopens a terminal **run** status — a restored workflow whose run status is terminal stays sealed
  (any subsequent transition is rejected by `assertNotSealed`).

### 2d. Linked-successor helper + documentation (resume = new linked workflow)

File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowInstance.kt`

- Add a pure helper next to `independentWorkflowRelations`:

  ```
  /** Relations of a successor workflow that resumes/re-runs a sealed predecessor. */
  fun linkedWorkflowRelations(
      newWorkflowId: String,
      previousWorkflowId: String,
      rootWorkflowId: String = previousWorkflowId,
  ): Map<String, Any?> = mapOf(
      "rootWorkflowId" to rootWorkflowId,
      "previousWorkflowId" to previousWorkflowId,
  )
  ```

- KDoc must state: a terminal workflow is immutable; to resume or re-run a completed/failed/cancelled
  requirement the control plane starts a **new** `workflowId` whose `relations` carry
  `previousWorkflowId` (and the shared `rootWorkflowId`), via the existing
  `WorkflowStartCommand.relations` field — the sealed workflow is never reopened. No change to
  `WorkflowStartCommand`, `createWorkflowInstance`, or `CanonicalHash` is required; relations already
  flow through them (confirm by reading `CanonicalHash.workflowStartCommandHash`).

### 2e. Tests

New test file: `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowTerminalSealingTest.kt`
(follow `WorkflowServiceIntegrationTest.kt` for harness/wiring; HTTP-level attestation may follow
`WorkflowControllerHttpTest.kt`)

- Drive a governed instance to each terminal run status (`completed`, `failed`, `cancelled`) and assert
  a subsequent `transition` / `codeTransition` / human-resolution call throws
  `WorkflowException` with code `WORKFLOW_SEALED` (HTTP 409). Assert on `errorCode` only.
- Assert `restore()` of a `removed` projection whose run status is terminal succeeds at the lifecycle
  level but a following transition still throws `WORKFLOW_SEALED` (no reopen of the run).
- Pure-domain test for `WorkflowStatuses.isTerminal` / `TERMINAL` membership and for
  `linkedWorkflowRelations` shape (new file or extend
  `factory-service/src/test/kotlin/io/whozoss/factory/workflow/domain/` with a real path, e.g.
  `WorkflowTerminalGovernanceTest.kt`).
- Successor pattern: starting a **new** `workflowId` with
  `relations = linkedWorkflowRelations(new, previous)` creates a distinct instance that references the
  sealed predecessor and leaves the predecessor untouched (read it back).

Commit: `feat(workflow): seal terminal workflow instances and link successors instead of reopening`

---

## Requirement 3 — AgentOS runtime-state distinction & observability (inside `agentattempt/` only)

**Goal:** evaluate/document the distinction between AgentOS runtime states (active, closed/sealed,
archived, `KILLED`, `closedByUser`) vs the Factory's sealed terminal verdicts, and structure a
completed/archived/runtime-closed distinction for cockpit/consumer observability. **No edits to
`adapter/agentos/` or `agentos/`.** Replicate the AgentOS vocabulary as documented string constants
(mirroring how `VerdictDeriver`/`CaseEventView` already keep a local, import-free view) — do not import
`io.whozoss.agentos.sdk.caseFlow.CaseStatus`.

### 3a. Documentation + mapping type

New file: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/AgentOsRuntimeStateMapping.kt`

- A pure, no-I/O Kotlin `object` (or sealed classification + object) with rich KDoc that:
  - Enumerates the AgentOS runtime states as local string constants with provenance comments:
    `PENDING`, `RUNNING`, `IDLE` (runtime alive, quiescent), `KILLED` (terminal, permanently
    destroyed), `ERROR` (terminal), plus the consumer-facing distinctions `ARCHIVED` and
    `CLOSED_BY_USER` (document that AgentOS exposes `removed`/soft-close on `CaseDto`; keep these as
    documented labels, not imports).
  - Defines a `SealingClass` enum: `ACTIVE` (observing, not sealed), `RUNTIME_CLOSED`
    (runtime terminal but the Factory verdict may still be indeterminate — "no success by silence"),
    `COMPLETED` (Factory-sealed authoritative success via the capability channel), `ARCHIVED`
    (operator-retired record).
  - Provides `fun classify(agentAttemptStatus: AgentAttemptStatus): SealingClass` mapping the durable
    `AgentAttemptStatus` to its sealing class, and KDoc tables mapping each AgentOS runtime state to the
    verdict it may (and may NOT) produce — restating the core rule that a terminal `KILLED`/`ERROR` or
    silence never maps to success; only a structured capability-backed submission seals `COMPLETED`.
  - Explicitly documents that this type is the **consumer/cockpit observability contract** for the
    completed/archived/runtime-closed distinction, and that it is standalone (import-free) by design,
    exactly like `adapter/agentos/VerdictDeriver` keeps its own local event view.

### 3b. Surface the distinction on the public read model

File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt`

- Add **additive, defaulted** fields so existing serialization/consumers are unaffected:
  - `val terminal: Boolean = false` — `status`'s `AgentAttemptStatus.terminal`.
  - `val sealingClass: String = ...` — `AgentOsRuntimeStateMapping.classify(status).name` (or its wire
    form). Document that cockpit uses it to distinguish completed vs runtime-closed vs archived.
- Update `fun DurableAgentAttempt.toDto()` to populate both from `status`. Keep all existing fields and
  their order; append the new fields at the end. Confirm no snapshot/golden test in
  `agentattempt/` pins the DTO's exact field set before changing (grep the test tree); if one does,
  update it in the same commit.

### 3c. Tests

New test file: `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/domain/AgentOsRuntimeStateMappingTest.kt`

- Pure unit test: every `AgentAttemptStatus` maps to the expected `SealingClass`; `SUCCEEDED → COMPLETED`;
  `FAILED`/`INDETERMINATE`/`INTERRUPTED`/`SUPERSEDED → RUNTIME_CLOSED`; non-terminal statuses `→ ACTIVE`.
- Attest the "never success by silence" rule at the documentation/mapping level: no AgentOS
  terminal/quiescent state constant maps to `COMPLETED` through this type (only an authoritative
  Factory verdict `SUCCEEDED` does).
- Extend/boundary-test `toDto()`: a terminal attempt's DTO has `terminal = true` and the matching
  `sealingClass`; a running attempt has `terminal = false`, `sealingClass = ACTIVE`.

Commit: `feat(agentattempt): document AgentOS runtime-state sealing distinction for cockpit observability`

---

## Cross-cutting: trust-boundary identity invariant

No new work beyond attestation: `TenantScope` scoping, the capability-token binding
(`attemptId, workflowId, stepId, namespaceId, caseId, agentName, briefHash` → hash only), and the rule
that no identity is read from model-authored arguments are already enforced
(`Neo4jAgentStepResultRepository.submit` identity fencing, `AgentStepResultController` trust override).
Where a new sealing test issues/redeems a capability, assert the submission is still fenced to the
issued identity (reuse the mismatch assertions from `AgentStepResultServiceIntegrationTest.kt`) so the
"trust boundary identities are respected" invariant is covered alongside the sealing checks.

---

## Verification (builder runs locally only to debug; the factory runs the gate)

1. `cd factory-service && ./gradlew test --tests '*Sealing*' --tests '*TerminalGovernance*' --tests '*RuntimeStateMapping*'`
   — judge by exit status.
2. Full module: `pnpm nx test factory-service`.
3. Lint/build via the factory gate commands:
   - `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
   - `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`
4. Confirm no file outside the two target package trees was modified (`git status` — only
   `agentattempt/` and `workflow/` main + test paths, plus this spec under `specs/`).
5. Confirm every path named in a test's KDoc/`describedAs` is a real path (strict build-gate claim).

## Commit sequence (Conventional Commits)

1. `feat(agentattempt): seal result-path attempt terminalize and attest attempt immutability` (Req 1)
2. `feat(workflow): seal terminal workflow instances and link successors instead of reopening` (Req 2)
3. `feat(agentattempt): document AgentOS runtime-state sealing distinction for cockpit observability` (Req 3)

(Keep the spec-document commit separate / as directed by the harness.)
