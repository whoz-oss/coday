# Plan — Phase 4: Workstream Agent & Governed Factory — Ask Human Step Question & Resumption Workflow

> **Nature: NEW IMPLEMENTATION** (not validation). All work is in Kotlin under
> `factory-service/` plus the AgentOS worker tool under
> `agentos/agentos-factory-bridge-plugin/`. Build/test is Gradle via Nx.
> **Do NOT touch** DB migrations (`V*__`, Flyway, `Neo4jSchemaInitializer` constraints
> unless strictly additive and necessary — see §9), `.github/`, release pipeline.

---

## 0. Goal (restated concisely)

Let a **worker** executing a step attempt ask a *structured human question*. Doing so:
1. Transitions the **current attempt N** to `WAITING_HUMAN` (status already exists in the
   enum — see §1 finding).
2. Persists a durable `HumanInteraction` (type `agent_question`) linked to
   `interactionId / attemptId / stepId / workflowId / namespaceId` **in the same logical
   operation** — without blocking a worker call in memory. The worker tool returns
   immediately after the question is durably recorded.
3. When an **authorized human** replies (audited `actorId`, single-use, revision-safe):
   - Attempt N is marked **superseded** (new terminal status) — never reactivated or rewritten.
   - **Attempt N+1** is created (`attemptNumber = N.attemptNumber + 1`) with a bounded
     **resumption context** (question + answer + human actor details), placed in
     `pending`/`running` ready for the worker to resume.
4. Cockpit/query endpoints expose the question, answer, interaction link, and successor
   attempt.
5. Notifications (outbox/SSE) are emitted but are **not** a hard correctness prerequisite.

---

## 1. Recon findings (confirmed first-hand — baseline `7faadc0e`)

### 1.1 Attempt subsystem (`factory-service/.../agentattempt/`)
- **`AgentAttemptStatus`** (`domain/AgentAttemptStatus.kt`) ALREADY has `WAITING_HUMAN("waiting_human")`.
  Transitions: `RUNNING ↔ WAITING_HUMAN`, and `WAITING_HUMAN → {SUCCEEDED, FAILED, INDETERMINATE, INTERRUPTED}`.
  Terminal set = `{SUCCEEDED, FAILED, INDETERMINATE, INTERRUPTED}`.
  **There is NO `SUPERSEDED` status.** We will ADD one (see §3.1).
- **Attempt numbering / resumption infra already exists**:
  - `DurableAgentAttemptService.nextAttemptNumber(scope, ns, wf, step)` → `MAX(attemptNumber)+1`.
  - `DurableAgentAttemptService.registerRetry(scope, attempt)` — registers a brand-new attempt,
    asserts `attemptNumber == next`, rejects a reused `attemptId` with `IdempotencyKeyCollisionException`.
    **"the prior attempt — terminal or not — is left strictly untouched."** This is exactly the
    superseding/resumption primitive we reuse.
  - `CapabilityExecutionService.retryAttemptId(wf, step, attemptNumber)` →
    attempt #1 = `"$wf#$step"`, retry (≥2) = `"$wf#$step#$attemptNumber"`.
  - `CapabilityExecutionService.stableAttemptId(wf, step)` = `"$wf#$step"`.
- **`DurableAgentAttempt`** (`domain/DurableAgentAttempt.kt`) is the authoritative durable attempt.
  It carries `brief: String?` (the turn brief captured at registration). **There is NO
  resumption-context field.** We will ADD one (`resumptionContext: String?`, bounded JSON) — see §3.1.
- **`DurableAgentAttemptNode`** (`persistence/DurableAgentAttemptNode.kt`) mirrors the domain to
  Neo4j; composite id `(org, workstream, ns, wf, step, attemptId)`. Status persisted as
  `dbValue` string, `revision` = optimistic lock.
- **`DurableAgentAttemptDto`** (`domain/DurableAgentAttemptDto.kt`) = bounded Cockpit read model
  (no secrets). `toDto()` maps domain→DTO. We extend this (see §6).
- **Step result** (`AgentStepResultService` + `AgentStepResultController` at
  `POST /api/factory/agent-step-results`): capability-bound, PASS/FAIL only, single-use capability
  (`SUBMISSION_BUDGET=1`), idempotency via `X-Idempotency-Key` + `idempotency_records`.
  `AgentStepResultStatus` enum = `{PASS, FAIL}`; validation `AgentStepResultValidation.STATUSES = {"PASS","FAIL"}`.
- **TrustContext/identity**: HTTP boundary resolves `TenantScope` + `actorId` + trusted
  `namespaceId`/`caseId` from verified `TrustContext`; never from model input. The worker
  tool injects identities from `ToolContext` (caseId, namespaceId, agentName) + capability binding.

### 1.2 HumanInteraction + Workflow (`factory-service/.../workflow/`)
- **`HumanInteractionRecord`** (`domain/WorkflowModels.kt`): fields
  `interactionId, namespaceId, workflowId, stepId, interactionType, status, revision, payload: Map`.
  **No dedicated `attemptId`, `answer`, `actorId`, `answeredAt`, `expiresAt` columns** — those
  live inside the free-form `payload` JSON. We follow this convention (store question/answer/
  attempt link in `payload`).
- **`HumanInteractionNode`** (`persistence/HumanInteractionNode.kt`): Neo4j `:HumanInteraction`
  node, composite id `(org, workstream, ns, wf, interactionId)`, `payload` = raw JSON string,
  `createdAt/updatedAt`. Append-only `:HumanInteractionEvent` journal via
  `HumanInteractionEventRecord(eventId, interactionId, eventType, actorId, payload)`.
- **`HumanInteractionRepository`** (`persistence/WorkflowRepository.kt`): `find`, `list`,
  `insert` (idempotent on composite id), `update` (CAS on `expectedRevision`), `appendEvent`,
  `listEvents`. `Neo4jHumanInteractionRepository` implements it.
- **Existing flows in `WorkflowService.kt`**:
  - `openInteraction(...)` (approval checkpoint, type `"approval"`, status `"waiting"`,
    actions approve/reject).
  - `replyInteraction(...)` → `replyInteractionTransactional(...)`: TWO-phase: a short
    `@Transactional` reply (evidence + governed transition + interaction closure), then a
    post-commit `resumeCheckpointSession(...)`. Single-use via step-status guard
    (`waiting_human`/`blocked`) + revision CAS; audited `actorId`.
  - `submitAgentQuestionAnswer(scope, ns, wf, step, questionEventId, answer, actorId)` — the
    EXISTING AgentOS question/answer path. **Important distinction**: it resumes the SAME attempt
    via `adapter.answerQuestion(...)` (AgentOS case continuation); it does NOT supersede or create
    attempt N+1. Phase 4 is a NEW path (durable supersede + N+1), independent from this.
- **Endpoints** (`WorkflowController.kt`, base `/api/factory/workflows`):
  - `POST /{workflowId}/interactions` (open), `POST /{workflowId}/interactions/{interactionId}/reply`,
    `POST /{workflowId}/agent-questions/{questionEventId}/answer`, `GET /{workflowId}/interactions`,
    `GET /{workflowId}/attempts`.
  - `replyInteraction` body = `{expectedRevision, actionId, text}`; actor from TrustContext;
    guarded by `isSafeActor` + `PRINCIPAL_TYPE_HUMAN`.
- **SSE**: `WorkflowSseHub.publish(scope, namespaceId, payload, event=UPDATED)` deferred to
  `afterCommit`. Best-effort, in-memory. Events in `WorkflowProjectionEvents`.
- **Test template**: `src/test/kotlin/io/whozoss/factory/workflow/AgentQuestionAnswerServiceTest.kt`
  shows the mockk pattern for `WorkflowService` with mocked repositories/services. Reuse it.

### 1.3 Worker bridge (`agentos/agentos-factory-bridge-plugin/`)
- Tools are `FACTORY__*`, built by `buildFactoryTools(services)` in `FactoryToolPlugin.kt`,
  granted explicitly by `FactoryToolGrantService`. All implement
  `io.whozoss.agentos.sdk.tool.StandardTool<Input>` with `name`, `description`, `version`,
  `paramType`, `inputSchema` (JSON string), and `suspend fun execute(input, context): ToolExecutionResult`.
- **`FactorySubmitStepResultTool`** (`tools/FactorySubmitStepResultTool.kt`): `FACTORY__submit_step_result`.
  Resolves `caseId` from `context.caseEvents`, `agent` from `context.agentName`, acquires the
  capability binding via `bindings.acquire(caseId, ns, agent)`, POSTs
  `{attemptId, result}` to `/api/factory/agent-step-results` with
  `Authorization: Bearer <capabilityToken>`, `X-AgentOS-Case-Id`, `X-AgentOS-Agent-Name`.
  Identity is injected from `ToolContext` + binding, NEVER from model args.
- **`FactoryStepResultBindingRegistry`**: durable, restart-safe (write-through
  `FactoryBridgeStateStore`), case-scoped capability bindings carrying `attemptId`.
- **`FactoryRequestHumanDecisionTool` + `FactoryAnswerInterceptor` + `FactoryCheckpointClient`**:
  the EXISTING checkpoint pattern. `FactoryCheckpointClient.submitDecision(...)` POSTs to
  `/api/factory/workflows/{wf}/interactions/{id}/reply` with `{interactionRevision, decision}`.
- **`FactoryAnswerAwaiter`/`FactoryAwaitAnswer`**: a control-flow suspend signal for in-memory
  await (`AgentInterrupt.AwaitAnswer`). **Phase 4 must NOT rely on this** — the worker tool must
  return promptly after durably recording the question (requirement: no blocked worker call).
- Wiring: `FactoryBridgeServices(config, objectMapper, httpClient, stepResultBindings, pendingCheckpoints)`.

---

## 2. Design decision (Requirement 2 — document in KDoc)

**Chosen: Option 2 variant — a DEDICATED worker tool `FACTORY__ask_step_question` + a dedicated
Factory endpoint, NOT overloading the PASS/FAIL step-result.**

Rationale (put this in KDoc of both the tool and the service):
- The structured step-result (`AgentStepResultStatus = {PASS, FAIL}`, `AGENT_STEP_RESULT_LIMITS`,
  single-use capability budget 1) is a *terminal business verdict*. Asking a question is NOT a
  verdict and must not consume the result capability nor terminalize the attempt. Overloading it
  with `WAITING_HUMAN` would blur the "success only from running/waiting_human" invariant and the
  single-submission budget.
- A dedicated tool keeps the result channel's semantics intact and lets `ask_step_question` be a
  separate WORKER capability (grantable/deniable independently; never granted to the Workstream Agent).
- It mirrors the existing clean split between `FACTORY__submit_step_result` and
  `FACTORY__request_human_decision`.

**New Factory endpoint**: `POST /api/factory/agent-step-questions` (capability-bound, same
`Authorization: Bearer <capability>` + `X-AgentOS-*` identity model as the step-result controller),
served by a new `AgentStepQuestionController` delegating to a new `AgentStepQuestionService`. This
keeps the worker→Factory contract symmetric with the step-result channel and reuses capability
verification (`AgentStepResultService.resolveCapability`) to bind the question to the exact attempt.

> We deliberately **do not** reuse `AgentStepResultStatus` with a `WAITING_HUMAN` member; the KDoc
> must state this explicitly so a future reader understands the single-authority / single-use rationale.

---

## 3. Factory-service changes (main)

### 3.1 Domain — StepQuestion model + validation + SUPERSEDED status + resumption context

**New file** `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/StepQuestion.kt`
- `enum class StepQuestionType { FREE_TEXT, SINGLE_CHOICE, OPEN_CHOICE }` with `fromWire`.
- `data class StepQuestion(prompt, type, options: List<String>, recipientRole: String?, contextHash: String, expiresAt: Instant?)`.
- `object StepQuestionLimits { const val PROMPT = 2000; const val OPTIONS = 20; const val OPTION_LENGTH = 500; const val RECIPIENT_ROLE = 128; const val CONTEXT_HASH = 128 }`.
- `object StepQuestionValidation { fun validate(node: JsonNode?): Boolean ; fun parse(node): StepQuestion }`
  — strict (unknown field rejected, `additionalProperties:false` semantics), prompt 1..2000,
  options required & non-empty for `SINGLE_CHOICE`, ≤20 options each 1..500 chars, recipientRole ≤128,
  contextHash required (hash string), expiresAt optional ISO instant in the future. Mirror the
  defensive style of `AgentStepResultValidation`.
- Error codes (new `object` or extend `AgentAttemptErrorCodes`): `QUESTION_SCHEMA_INVALID` (400),
  `QUESTION_CAPABILITY_INVALID` (401 — reuse), `QUESTION_ATTEMPT_NOT_WAITABLE` (409 — attempt not in
  a state that can ask), `QUESTION_ALREADY_ASKED` (409 idempotent-collision),
  `QUESTION_ALREADY_ANSWERED` (409), `QUESTION_INTERACTION_STALE` (409),
  `QUESTION_SUPERSEDE_CONFLICT` (409). Add matching `*Exception` classes extending
  `AgentAttemptException`/`FactoryException` so the canonical error envelope renders.

**Edit** `domain/AgentAttemptStatus.kt`:
- Add `SUPERSEDED("superseded")` enum member.
- Mark it `terminal` (add to the `terminal` getter set).
- Add transition `WAITING_HUMAN → SUPERSEDED` to `ALLOWED_TRANSITIONS` (and `SUPERSEDED → emptySet()`).
  Keep `SUCCEEDED` reachable only from `RUNNING`/`WAITING_HUMAN` (do NOT add SUPERSEDED as a success source).
- Update KDoc to document SUPERSEDED as an immutable terminal "handed off to attempt N+1" record.

**Edit** `domain/DurableAgentAttempt.kt`:
- Add `val resumptionContext: String? = null` (bounded JSON string: question, answer, actorId,
  answeredAt, predecessorAttemptId, predecessorInteractionId). Document bound (e.g. ≤8 KiB) and
  that it is set once at N+1 registration, never mutated.

**Edit** `persistence/DurableAgentAttemptNode.kt`:
- Add `val resumptionContext: String? = null`; map it in both `toDomain()` and `fromDomain()`.
- Confirm `AgentAttemptStatus.fromDbValue("superseded")` round-trips (it will via the enum).

**Edit** `domain/DurableAgentAttemptDto.kt`:
- Add `resumptionContext: String? = null` (bounded, no secrets) and a nullable
  `successorAttemptId: String? = null` and `questionInteractionId: String? = null`
  so Cockpit sees the link. Map them in `toDto()` (successor/interaction may be derived by the
  service layer rather than stored — see §6; keep DTO fields nullable).

### 3.2 Persistence — Neo4j CAS statements for superseding

**Edit** `persistence/DurableAgentAttemptRepository.kt` (port interface) and
`persistence/Neo4jDurableAgentAttemptRepository.kt` (+ `SpringDataNeo4jDurableAgentAttemptRepository.kt`):
- Add a repository method `supersede(scope, ns, wf, step, attemptId, expectedRevision?, now): DurableAgentAttempt`
  that CAS-transitions an attempt currently in `WAITING_HUMAN` to `SUPERSEDED`, bumps `revision`,
  sets `completedAt`. Must be **idempotent** when already `SUPERSEDED` and must **reject** any other
  terminal status (immutability). Follow the existing `cancel(...)` / `finalize(...)` Cypher style
  (fenced CAS, `revision = revision + 1`). **Append a journal entry** for the landed
  `WAITING_HUMAN -> SUPERSEDED` transition exactly as `transition`/`finalize`/`cancel` do (the
  journal is appended only on a landed CAS; `DurableAgentAttemptJournalEntry` already carries
  `fromStatus/toStatus/ownerToken/revisionAfter/recordedAt`). An idempotent re-supersede appends
  nothing.
- Add `recordResumptionContext` as part of N+1 registration path — simplest is to set
  `resumptionContext` directly on the `DurableAgentAttempt` passed to `register(...)`/`registerRetry(...)`
  (no new method needed; the field flows through `fromDomain`). Verify the `register` Cypher writes
  all node properties (it uses `DurableAgentAttemptNode.fromDomain`, so the new nullable field is
  included automatically).

### 3.3 Service — AgentStepQuestionService (worker asks) + answer/supersede/resume

**New file** `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/AgentStepQuestionService.kt`

Responsibilities (all `@Transactional` where they mutate):

**(a) `ask(scope, token, attemptId, questionNode, observed, idempotencyKey, now)`** — called by the
new controller for the worker:
1. Resolve capability binding via `AgentStepResultService.resolveCapability(scope, token)`; verify
   it binds to `attemptId` (reuse the identity-fence pattern of `AgentStepResultController`:
   trusted namespace/case override + mismatch → `RESULT_IDENTITY_MISMATCH`). Reject unknown/expired
   capability.
2. Validate the question (`StepQuestionValidation.validate`), else `QUESTION_SCHEMA_INVALID`.
3. Load the attempt (`DurableAgentAttemptService.find`). It must be in `RUNNING` (or already
   `WAITING_HUMAN` for idempotent replay). Otherwise `QUESTION_ATTEMPT_NOT_WAITABLE`.
4. **Same logical operation (one `@Transactional`)**:
   a. `attempts.transition(... target = WAITING_HUMAN ...)` fenced on the attempt's owner token
      (reuse `DurableAgentAttemptService.transition`). Note: the owner token comes from the
      durable attempt (lease owner), not from model input.
   b. Persist a durable `HumanInteractionRecord` with `interactionType = "agent_question"`,
      `status = "waiting"`, `revision = attempt.revision` (or workflow revision — pick the attempt
      revision as the interaction optimistic lock, consistent with `openInteraction`), and a
      `payload` carrying: `attemptId`, `stepId`, `prompt`, `questionType`, `options`,
      `recipientRole`, `contextHash`, `expiresAt`, `idempotencyKey`. Use a deterministic
      `interactionId` derived from `(attemptId, contextHash)` or a UUID stored under an
      idempotency guard so a replay returns the same interaction (idempotency: reuse the
      `idempotency_records` layer like `AgentStepResultService`, OR rely on
      `HumanInteractionRepository.insert` being idempotent on composite id — prefer a deterministic
      `interactionId = UUID.nameUUIDFromBytes("$attemptId#$contextHash")` so a double-ask collapses).
   c. `interactionRepository.appendEvent(... eventType="agent_question_asked", actorId="factory-worker" ...)`.
   d. Emit a deferred SSE hint (`sseHub.publish(scope, ns, {...}, UPDATED)`) and an outbox event
      (see §3.5) — non-blocking, after-commit.
5. Return `202` `{ attemptId, interactionId, status: "waiting_human" }`. The worker tool returns
   immediately — no in-memory await.

> Transaction boundary note: the attempt lives in the `agentattempt` aggregate and the interaction
> in the `workflow` aggregate, but both use the single Neo4j transaction manager
> (`Neo4jPersistenceConfiguration`). Wrap (a)+(b)+(c) in one `@Transactional` method so the attempt
> transition and the interaction insert commit atomically ("same logical operation").

**(b) `answer(scope, ns, wf, stepId, interactionId, answer, actorId, now)`** — called by the human
reply endpoint:
1. Load interaction; must be `interactionType = "agent_question"` and `status = "waiting"`, else
   `QUESTION_ALREADY_ANSWERED` / `INTERACTION_NOT_FOUND` / `QUESTION_INTERACTION_STALE`.
2. Validate `actorId` present (audited) and answer bounds/type (reuse the FREE_TEXT/SINGLE_CHOICE/
   OPEN_CHOICE validation style from `submitAgentQuestionAnswer`). Options enforced for SINGLE_CHOICE.
3. Resolve the predecessor attempt N via `payload.attemptId`. It must be `WAITING_HUMAN`.
4. **Single `@Transactional` (single-use + revision-safe)**:
   a. CAS-close the interaction: `interactionRepository.update(... expectedRevision ...,
      status = "closed", payload += {answer, actorId, answeredAt, successorAttemptId})`.
      (Use the EXISTING interaction status vocabulary `"waiting"`/`"closed"` — do NOT invent an
      `"answered"` string; mark the answered-ness via `payload.answeredAt`/`payload.answer`.)
      A stale revision → `REVISION_CONFLICT`. A second answer finds status `"closed"` → reject /
      idempotent (double-unblock protection; see §8).
   b. `attempts.supersede(... attemptId N ...)` → attempt N terminal `SUPERSEDED` (immutable).
   c. Compute `nextNumber = attempts.nextAttemptNumber(...)` and
      `newAttemptId = CapabilityExecutionService.retryAttemptId(wf, step, nextNumber)`.
   d. Build the bounded `resumptionContext` JSON `{question, questionType, options, answer, actorId,
      answeredAt, predecessorAttemptId: N, predecessorInteractionId}` (cap ≤8 KiB — truncate/validate).
   e. `attempts.registerRetry(scope, DurableAgentAttempt(newAttemptId, ... attemptNumber=nextNumber,
      status = PENDING, brief = N.brief, resumptionContext = <json>, caseId = N.caseId or a fresh
      stable case ...))`. (Keep caseId strategy consistent with existing retry: `registerRetry`
      just stores the record; the execution adapter later claims/starts it. Attempt N+1 starts in
      `PENDING` ready for the worker — do NOT auto-run inside this transaction.)
   f. `appendEvent(eventType="agent_question_answered", actorId=actorId, payload={successorAttemptId})`.
5. After commit (non-blocking): SSE hint + outbox event `agent_question_answered`. Optionally
   trigger resumption analogous to `resumeCheckpointSession` — but keep it OUTSIDE the transaction
   and non-fatal, and only if the existing session runner is wired; otherwise leaving N+1 `PENDING`
   satisfies "ready for the worker to resume".
6. Return `200` `{ interactionId, status: "closed", supersededAttemptId: N, successorAttemptId: N+1,
   successorAttemptNumber: nextNumber }`.

Identities: `actorId`, `attemptId`, `caseId`, `namespaceId` always come from `TrustContext`
(HTTP) or the capability binding, never from model-authored prompt strings. Document this in KDoc.

### 3.4 Web — controllers

**New file** `factory-service/.../agentattempt/web/AgentStepQuestionController.kt` +
`AgentStepQuestionDtos.kt`:
- `POST /api/factory/agent-step-questions` (capability-bound, `Authorization: Bearer`,
  `X-AgentOS-Case-Id`, `X-AgentOS-Agent-Name`, optional `X-Idempotency-Key`). Mirror
  `AgentStepResultController` structure exactly (bearer parse, trust-context fence, envelope
  `{data}`). Body `{ attemptId, question: {prompt, type, options?, recipientRole?, contextHash, expiresAt?} }`.
  Returns `202` `{data:{attemptId, interactionId, status}}`.

**Edit** `factory-service/.../workflow/web/WorkflowController.kt`:
- Add `POST /{workflowId}/agent-step-questions/{interactionId}/answer` (human reply to a worker
  step question). Body `{ expectedRevision, answer }` (and optional nothing else — reject unknown
  keys like `replyInteraction` does). Guard: `isSafeActor(actorId)` + `PRINCIPAL_TYPE_HUMAN`
  (same as `answerAgentQuestion`/`replyInteraction`). Delegate to
  `AgentStepQuestionService.answer(...)`. Resolve `caller` via `resolveWorkflowCaller`.
  - **Alternative** (also acceptable per the prompt): extend the existing
    `/{workflowId}/interactions/{interactionId}/reply` to detect `interactionType == "agent_question"`
    and route to the supersede/resume path. **Prefer the dedicated endpoint** to avoid overloading
    the approve/reject reply contract — document the choice.

> `AgentStepQuestionService` needs `DurableAgentAttemptService`, `HumanInteractionRepository`,
> `AgentStepResultService` (for `resolveCapability`), `WorkflowSseHub`, `ObjectMapper`, and the
> outbox writer. Wire it as a `@Service` (constructor injection); add it to `WorkflowService`
> construction only if the answer path lives there — otherwise keep it standalone and inject it
> into `WorkflowController`. **Preferred**: standalone `@Service` injected into both the new
> `AgentStepQuestionController` and `WorkflowController`.

### 3.5 Deferred notifications (Requirement 7)
- Reuse the existing outbox (`OutboxEventNode` / `SpringDataNeo4jOutboxRepository` /
  `OutboxDrainService`). Append `agent_question_asked` and `agent_question_answered` outbox events
  inside the respective transactions (so they are durable and drained asynchronously). Confirm the
  outbox append API used by `AgentStepResultService.submit` / result path and follow it.
- Also publish best-effort `WorkflowSseHub.publish(scope, ns, payload)` after commit.
- **Delivery is NOT a correctness prerequisite** — the core transitions must succeed even if SSE
  has no subscribers / drain is pending.

---

## 4. Worker tool changes (`agentos/agentos-factory-bridge-plugin/`)

**New file** `src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryAskStepQuestionTool.kt`:
- `class FactoryAskStepQuestionTool(baseUrl, http, mapper, bindings: FactoryStepResultBindingRegistry) : StandardTool<Input>`.
- `name = "FACTORY__ask_step_question"`, `version = "1.0.0"`, description noting attempt identity is
  runtime-injected and this suspends the step for a human answer **durably** (no blocked call).
- `data class Input(prompt: String, type: String = "FREE_TEXT", options: List<String> = emptyList(), recipientRole: String? = null)`.
- `inputSchema`: `additionalProperties:false`, `prompt` 1..2000, `type` enum
  `["FREE_TEXT","SINGLE_CHOICE","OPEN_CHOICE"]`, `options` ≤20 items ≤500 chars, `recipientRole` ≤128.
  **No identity fields in the schema.**
- `execute`: resolve `caseId` from `context.caseEvents.single`, `agent` from `context.agentName`,
  acquire binding (`bindings.acquire`), compute a `contextHash` (e.g. canonical hash of the input +
  attemptId — deterministic for idempotent re-ask), POST
  `{attemptId: binding.attemptId, question:{prompt,type,options,recipientRole,contextHash}}` to
  `/api/factory/agent-step-questions` with `Authorization: Bearer <token>`, `X-AgentOS-Case-Id`,
  `X-AgentOS-Agent-Name`, `X-Idempotency-Key: <contextHash>`. On 2xx return success with the
  `{interactionId, status}` payload; the tool **returns promptly** (does NOT await the answer —
  this is the key difference from `FactoryAwaitAnswer`). Mirror the error-mapping/binding
  release/acknowledge logic of `FactorySubmitStepResultTool` but **do not** `acknowledge` (consume)
  the result capability — use `release` so the result binding stays available for the resumed
  attempt N+1 to later submit its PASS/FAIL. (Confirm binding lifecycle: a question is not a result,
  so the step-result capability must NOT be consumed. Use `release`, not `acknowledge`.)
  - **CRITICAL binding-model fact (recon):** the step-result capability binding is created
    OUT-OF-BAND by `FactoryBindingRegistrar` + `FactoryStepResultBindingController` from the
    `X-Factory-Attempt-Id` / `X-Factory-Capability-Token` headers at case creation, and the
    `FactoryStepResultBindingRegistry` is keyed by **`caseId`** (one binding per case), carrying a
    single `attemptId`. Attempt N+1 has a NEW `attemptId`. Therefore the builder MUST decide how
    N+1 obtains its capability binding and consider the caseId strategy together (see §3.3 step e):
    either (a) N+1 reuses N's `caseId` so the existing case binding still resolves (then the
    binding's stored `attemptId` is N's, not N+1's — a mismatch to resolve), or (b) a fresh case +
    fresh binding is minted for N+1 at reservation via the normal attempt-start path. **Flag this
    to confirm against `FactoryStepResultBindingController` / the attempt-start capability issuance
    before coding** — it is the one cross-boundary unknown in this plan. The `ask_step_question`
    tool itself only `acquire`+`release`s the EXISTING case binding to read `attemptId`; it never
    mints or consumes a binding.

> **Binding lifecycle (confirmed via recon):** `FactorySubmitStepResultTool` uses
> `acquire` then `acknowledge` (removes the binding, consuming the single-use result capability).
> `FACTORY__ask_step_question` must instead `acquire` then `release` on a 2xx so the result
> capability stays available — asking a question is NOT a terminal result. Use `invalidate` only on
> non-retryable 4xx and `release` on 5xx/network errors, mirroring the submit tool's error mapping.
> **Unlike `FactoryRequestHumanDecisionTool`** (which accepts model-authored `workflowId`/`stepId`/
> `expectedRevision` as workflow coordinates), `ask_step_question` needs NONE of those: `attemptId`
> comes from the binding and all other identity from `ToolContext`. Keep the input schema to
> `prompt`/`type`/`options`/`recipientRole` only.

**Edit** `FactoryToolPlugin.kt`:
- Add `FactoryAskStepQuestionTool(baseUrl, httpClient, objectMapper, services.stepResultBindings)`
  to the `buildFactoryTools(...)` list.

**Edit** `FactoryToolGrantService.kt`:
- Ensure the new tool is included in the FACTORY tool set so it can be granted; confirm it is a
  **WORKER** capability (grant it only alongside `submit_step_result`, never to the Workstream
  Agent persona). Document in KDoc/grant policy.

> Keep using `ToolContext` identities only. No model-authored identity. `contextHash` is computed
> by the tool from trusted inputs, not supplied by the model.

---

## 5. Safety & invariants (Requirement 8 — enforce + assert in tests)
- Factory is the sole authority: all state transitions happen in `factory-service` services under
  transactions + revision CAS. The worker tool only POSTs a request.
- Identities (`attemptId`, `interactionId`, `caseId`, `namespaceId`, `actorId`) come from
  `TrustContext`/capability binding/`ToolContext`, never from prompt strings. Assert in tests that
  a declared-caseId mismatch is rejected with `RESULT_IDENTITY_MISMATCH`.
- Attempt N: once `SUPERSEDED` it is terminal and immutable — the `supersede` CAS rejects any
  further transition; `registerRetry` guards against reusing attempt ids.
- `ask_step_question` is a WORKER tool, never granted to the Workstream Agent (enforced in grant
  service; assert in a plugin test if a grant test harness exists).

---

## 6. Cockpit & query visibility (Requirement 6)
- `DurableAgentAttemptDto` gains `resumptionContext`, and the service that lists attempts
  (`GET /{workflowId}/attempts` via `DurableAgentAttemptService.findByWorkflow` → DTO) must surface
  the successor link. Simplest: derive `successorAttemptId`/`questionInteractionId` in the DTO
  mapping at the service/controller layer by matching attemptNumbers and interaction payloads, OR
  store `successorAttemptId` in attempt N's node at supersede time. **Prefer** storing the
  predecessor link in N+1's `resumptionContext` (already done) and exposing the open
  `agent_question` interactions through the existing `GET /{workflowId}/interactions` list (they
  already serialize `payload`, which now contains prompt/answer/attemptId). Confirm
  `interactionType == "agent_question"`.
  - **IMPORTANT (recon):** the private `WorkflowService.HumanInteractionRecord.toJson()` currently
    emits only `{interactionId, workflowId, stepId, interactionType, status, revision, actions,
    prompt, response?}` and OMITS `namespaceId`/`actorId`/`answeredAt`. For `agent_question`
    interactions you MUST extend `toJson()` to also surface the question/answer payload fields
    (e.g. `questionType`, `options`, `answer`, `answeredAt`, `actorId`, `attemptId`,
    `successorAttemptId`) so Cockpit can display the full Q&A + the N+1 link. Keep it bounded /
    no secrets.
- Add a `GET` convenience is optional; the existing interactions + attempts endpoints already cover
  query needs once the new fields/types are present. Document the Cockpit query recipe in KDoc.

---

## 7. Tests (Requirement — acceptance criteria). Place under
`factory-service/src/test/kotlin/io/whozoss/factory/` and the plugin's `src/test/kotlin/...`.

Follow the mockk/JUnit5/AssertJ style of `AgentQuestionAnswerServiceTest.kt` for unit tests, and
the Neo4j integration-test harness used by `DurableAgentAttempt*Test.kt` /
`AgentStepResultServiceIntegrationTest.kt` / `WorkflowServiceIntegrationTest.kt` for durability.

1. **`StepQuestionValidationTest.kt`** (`agentattempt/domain/`): schema bounds — prompt length,
   options count/length, type enum, SINGLE_CHOICE requires options, unknown fields rejected,
   expiresAt parsing.
2. **`AgentAttemptStateMachineTest.kt`** (EDIT existing `agentattempt/domain/`): add cases for
   `WAITING_HUMAN → SUPERSEDED` allowed, `SUPERSEDED` terminal, `SUPERSEDED` not a success, and
   `SUCCEEDED` still not reachable from SUPERSEDED/PENDING.
3. **`AgentStepQuestionServiceTest.kt`** (new, `agentattempt/`): unit — mocked repos/services —
   proves: ask transitions attempt to `WAITING_HUMAN` + inserts `agent_question` interaction in the
   SAME transactional call (verify order/atomicity via mock verification); identity mismatch
   rejected; schema invalid rejected; re-ask with same contextHash is idempotent (one interaction).
4. **`AgentStepQuestionAnswerServiceTest.kt`** (new): unit — answer is audited (`actorId` recorded),
   single-use (second answer rejected: `QUESTION_ALREADY_ANSWERED`), revision-safe
   (`REVISION_CONFLICT` on stale), marks N `SUPERSEDED`, creates N+1 with bounded
   `resumptionContext` containing question+answer+actor, N+1 `attemptNumber == N+1`, N+1 in
   `PENDING`. **Double-unblock**: answering twice never creates attempt N+2 (assert
   `nextAttemptNumber`/`registerRetry` invoked exactly once).
5. **`AgentStepQuestionDurabilityTest.kt`** (new, Neo4j integration): persist attempt N
   `WAITING_HUMAN` + `agent_question` interaction, reconstruct repositories (simulate restart like
   the existing durability tests), assert the question + waiting interaction survive; then answer
   and assert N `SUPERSEDED` + N+1 persisted with resumption context.
6. **`AgentStepQuestionControllerHttpTest.kt`** (new): capability-bound POST
   `/api/factory/agent-step-questions` happy path (202) + capability invalid (401) + schema invalid
   (400); and the human answer endpoint on `WorkflowController` (200 + 401 unauthenticated actor +
   409 already answered). Mirror `AgentStepResultControllerHttpTest.kt` /
   `WorkflowControllerHttpTest.kt`.
7. **Plugin** `FactoryAskStepQuestionToolSpec.kt` (new, mirror `FactorySubmitStepResultToolSpec.kt`):
   builds the correct POST body/headers from `ToolContext` + binding, returns promptly on 2xx, maps
   errors, never reads identity from input, and does not consume the result capability.
8. **Regression**: all existing `factory-service` + plugin tests must still pass. Pay attention to
   `AgentAttemptStateMachineTest` (enum change), `DurableAgentAttemptProjectionTest`/`...Node`
   (new nullable field), and any exhaustive `when(status)` over `AgentAttemptStatus` — **grep for
   `AgentAttemptStatus.` `when` branches** and add `SUPERSEDED` where the compiler requires it
   (e.g. mappings in `WorkflowActionsModels`, blockers, DTO conversions). This is the most likely
   source of compile breaks.

---

## 8. Double-unblock protection (Requirement 5.5)
- The answer transaction CAS-updates the interaction `status` from `"waiting"` → `"closed"` on
  `expectedRevision`. A concurrent/second answer either hits `REVISION_CONFLICT` (stale) or finds
  `status != "waiting"` (i.e. already `"closed"`) → `QUESTION_ALREADY_ANSWERED`. Because `supersede(N)` + `registerRetry(N+1)`
  run only inside the winning transaction, a second answer can never create attempt N+2.
- `registerRetry` additionally rejects a duplicate `attemptId` (`IdempotencyKeyCollisionException`),
  a second safety net. Assert both in test #4.

---

## 9. Schema / migration note
- Neo4j is schemaless for properties, so adding `resumptionContext` to `DurableAgentAttemptNode`
  and new interaction `payload` keys needs **no migration**. New node labels are not introduced
  (reuse `:HumanInteraction` / `:HumanInteractionEvent`, `:DurableAgentAttempt`).
- **Do NOT** add/modify Flyway `V*__` files or edit `Neo4jSchemaInitializer` constraints unless a
  test proves a required uniqueness constraint is missing — in which case flag it and ask before
  touching migrations. Default assumption: no schema change needed.

---

## 10. Execution order (suggested commits — conventional, focused)
1. `feat(agentattempt): add SUPERSEDED status and StepQuestion domain + validation` — enum,
   `StepQuestion.kt`, errors, exhaustive-`when` fixes, `AgentAttemptStateMachineTest` +
   `StepQuestionValidationTest`.
2. `feat(agentattempt): persist resumption context and supersede on durable attempts` — domain
   field, `DurableAgentAttemptNode`, repository `supersede(...)`, `DurableAgentAttemptDto`, Neo4j
   repo + Spring Data method; durability test.
3. `feat(agentattempt): AgentStepQuestionService + capability-bound ask endpoint` — service,
   controller, DTOs, outbox+SSE hooks; unit + HTTP tests.
4. `feat(workflow): human answer endpoint supersedes attempt N and resumes as N+1` —
   `WorkflowController` endpoint + wiring; answer-service test + HTTP test.
5. `feat(agentos): FACTORY__ask_step_question worker tool` — tool, plugin registration, grant
   policy; plugin spec.
6. `test(factory): phase4 integration + regression green` — any remaining integration tests / fixes.

> Each commit must build & pass its own tests before the next. The factory runs the full affected
> test suite after the build; still run `pnpm nx test factory-service` locally while iterating, and
> `pnpm nx test agentos-factory-bridge-plugin` for the tool.

---

## 11. Verification
- `pnpm nx test factory-service` (Gradle via Nx) — all green incl. new tests.
- `pnpm nx test agentos-factory-bridge-plugin` — plugin spec green.
- `pnpm nx affected -t build --base="$(cat /work/data/baseline)"` — compiles.
- `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`.
- `git status` / `git diff` must list exactly the files named in build claims — declare real paths.
- Confirm no `V*__` migration, `.github/`, or release-pipeline file is touched.

---

## 12. Key file inventory (touch list)

**factory-service — new:**
- `src/main/kotlin/io/whozoss/factory/agentattempt/domain/StepQuestion.kt`
- `src/main/kotlin/io/whozoss/factory/agentattempt/service/AgentStepQuestionService.kt`
- `src/main/kotlin/io/whozoss/factory/agentattempt/web/AgentStepQuestionController.kt`
- `src/main/kotlin/io/whozoss/factory/agentattempt/web/AgentStepQuestionDtos.kt`
- tests: `agentattempt/domain/StepQuestionValidationTest.kt`,
  `agentattempt/AgentStepQuestionServiceTest.kt`,
  `agentattempt/AgentStepQuestionAnswerServiceTest.kt`,
  `agentattempt/AgentStepQuestionDurabilityTest.kt`,
  `agentattempt/AgentStepQuestionControllerHttpTest.kt`

**factory-service — edit:**
- `agentattempt/domain/AgentAttemptStatus.kt`
- `agentattempt/domain/DurableAgentAttempt.kt`
- `agentattempt/domain/DurableAgentAttemptDto.kt`
- `agentattempt/domain/AgentStepResultModels.kt` (error codes, if extending `AgentAttemptErrorCodes`)
- `agentattempt/persistence/DurableAgentAttemptNode.kt`
- `agentattempt/persistence/DurableAgentAttemptRepository.kt`
- `agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt`
- `agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`
- `workflow/web/WorkflowController.kt` (answer endpoint)
- possibly `workflow/service/WorkflowService.kt` (only if answer path is hosted there)
- `agentattempt/domain/AgentAttemptStateMachineTest.kt` (edit)
- any file with an exhaustive `when (AgentAttemptStatus)` (grep first)

**agentos-factory-bridge-plugin — new/edit:**
- new `tools/FactoryAskStepQuestionTool.kt`
- edit `FactoryToolPlugin.kt`, `FactoryToolGrantService.kt`
- new test `FactoryAskStepQuestionToolSpec.kt`

---

## 13. Open choices the builder must lock (and document in KDoc)
1. **Dedicated tool + endpoint** vs overloading step-result → chosen: dedicated (see §2). Keep.
2. **Answer endpoint**: dedicated `/{wf}/agent-step-questions/{interactionId}/answer` vs reuse
   `/reply` → chosen: dedicated (see §3.4). Keep.
3. **resumptionContext bound**: pick a documented cap (≤8 KiB) and truncate/validate.
4. **N+1 caseId & auto-resume**: register N+1 as `PENDING` ready for the worker (do not force a
   synchronous run inside the answer transaction). Any session resumption trigger must be
   post-commit and non-fatal, mirroring `resumeCheckpointSession`.
5. **Owner token / lease on N+1**: register N+1 in `PENDING` with NO `ownerToken` (the predecessor's
   lease token must NOT carry over — attempt N is terminal/superseded). The worker re-claims N+1 via
   the normal `claim(...)` path, which establishes a fresh owner token. Do not attempt to drive
   `WAITING_HUMAN -> RUNNING` on attempt N; resumption is always a NEW attempt N+1, never a resume of N.
