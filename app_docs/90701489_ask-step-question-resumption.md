# Phase 4 — Ask Human Step Question & Resumption Workflow

Implements Phase 4 of the "Factory gouvernée & Workstream Agent" specification: a worker
executing a step attempt can now ask a structured human question, which parks the attempt in
a durable `waiting_human` status, and a governed human answer supersedes that attempt and
resumes the step as a brand-new attempt carrying a bounded resumption context. The full
design rationale lives in `specs/90701489_ask_step_question_resumption.md` (shipped in this
same change).

## What changed and why

### Design decision: a dedicated channel, not a step-result status

A `PASS`/`FAIL` step result is a terminal business verdict guarded by a single-use result
capability. Asking a question is not a verdict — it must neither consume that capability nor
terminalize the attempt. The change therefore keeps `AgentStepResultStatus` at exactly
`{PASS, FAIL}` (no `WAITING_HUMAN` member on the result contract) and adds a dedicated
channel end to end:

- Worker tool: `FACTORY__ask_step_question` (never granted to the Workstream Agent).
- Ask endpoint: `POST /api/factory/agent-step-questions` (HTTP 202, capability-bound).
- Answer endpoint: `POST /api/factory/workflows/{workflowId}/agent-step-questions/{interactionId}/answer`.

### Ask flow (worker → Factory)

The tool resolves the case-scoped step-result binding **read-only** — it `acquire`s and
`release`s the binding but never `acknowledge`s (consumes) or invalidates it, so the result
capability stays fully available for the resumed attempt's final `PASS`/`FAIL`. It computes a
deterministic `contextHash` (SHA-256 of attempt id + question content) from trusted inputs,
never from model-authored identity fields.

Server side, `AgentStepQuestionService.ask` resolves the capability read-only, fences the
declared attempt/case/agent/namespace identity against it, validates the question schema,
then in **one transaction**: transitions attempt N to `waiting_human` (fenced on the durable
lease owner token), inserts a durable `HumanInteraction` of type `agent_question` (linked by
`interactionId`/`attemptId`/`stepId`/`workflowId`/`namespaceId`), appends an
`agent_question_asked` audit event, and enqueues an outbox event. The `interactionId` is a
name-based UUID of `(attemptId, contextHash)`, so a retried ask of the same question
collapses onto the same interaction (idempotent replay, `idempotent: true` in the response);
a *different* question on an already-waiting attempt is rejected with
`QUESTION_ALREADY_ASKED`. The worker call returns as soon as the question is durably
recorded — nothing is awaited in memory.

### Answer flow (human → Factory)

`AgentStepQuestionService.answer` is revision-safe, audited and single-use, in **one
transaction**:

1. CAS-close the interaction (`waiting` → `closed`, optimistic lock on the caller's
   `expectedRevision`). A second answer finds it closed and is rejected with
   `QUESTION_ALREADY_ANSWERED` — execution unblocks exactly once, no attempt N+2 can ever
   be created. A lost CAS or stale revision surfaces as `REVISION_CONFLICT`.
2. Supersede attempt N: new terminal `SUPERSEDED` status, revision-fenced CAS, lease owner
   token rotated (`supersede:<attemptId>`) so an in-flight worker is fenced out. Attempt N
   is immutable — never reactivated or rewritten.
3. Register attempt N+1 via the existing `registerRetry` primitive
   (`attemptNumber = N + 1`, `pending`, **no carried-over owner token** — the worker
   re-claims through the normal lease path), carrying a bounded `resumptionContext` JSON
   (question, validated answer, audited `actorId`, `answeredAt`, predecessor
   `attemptId`/`interactionId`). Bound: `StepQuestionLimits.RESUMPTION_CONTEXT_BYTES` = 8192;
   the options list is dropped first if the bound would be exceeded (it stays on the
   interaction payload).

Answer validation: 1–2000 trimmed chars; for `SINGLE_CHOICE` questions the answer must be
one of the recorded options. The HTTP boundary requires an authenticated **human** principal
(`actorId` audited on the interaction event and the response).

### Visibility & notifications

- `DurableAgentAttempt` / `DurableAgentAttemptNode` / `DurableAgentAttemptDto` gain the
  `resumptionContext` field (persisted in Neo4j, exposed in the attempts DTO).
- `WorkflowService`'s interaction JSON projection surfaces the full Q&A chain for
  `agent_question` interactions (prompt, type, options, recipient, context hash, expiry,
  answer, actor, `answeredAt`, `successorAttemptId`, namespace) so Cockpit can display the
  attempt N → N+1 link.
- Outbox events (`agent_question_asked` / `agent_question_answered`, aggregate
  `agent_step_question`) are enqueued inside the transaction; a best-effort SSE hint is
  published after commit via the optional `WorkflowSseHub`. Delivery is never a correctness
  prerequisite.

## Files that carry it

**factory-service — domain**
- `.../agentattempt/domain/StepQuestion.kt` (new): `StepQuestionType` (`FREE_TEXT`,
  `SINGLE_CHOICE`, `OPEN_CHOICE`), `StepQuestionLimits`, `StepQuestion`, and pure
  `StepQuestionValidation`/`parse` (additionalProperties-false semantics, all bounds).
- `.../agentattempt/domain/AgentAttemptStatus.kt`: new terminal `SUPERSEDED("superseded")`,
  reachable only from `WAITING_HUMAN`; no outgoing transitions; not a success.
- `.../agentattempt/domain/AgentStepResultModels.kt`: `QUESTION_*` error codes and eight new
  typed exceptions (schema, not-waitable, already-asked, already-answered, not-found, stale,
  supersede-conflict, answer-invalid).
- `.../agentattempt/domain/DurableAgentAttempt.kt`, `DurableAgentAttemptDto.kt`:
  `resumptionContext` field and DTO mapping.

**factory-service — persistence**
- `.../persistence/DurableAgentAttemptNode.kt`: `resumptionContext` mapping.
- `.../persistence/DurableAgentAttemptRepository.kt`: `supersede(...)` port (revision-fenced,
  idempotent when already superseded).
- `.../persistence/Neo4jDurableAgentAttemptRepository.kt`: `supersede` implementation with
  precise conflict surfacing and journal entry.
- `.../persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`: `supersede` Cypher CAS
  (`status = 'waiting_human'` + revision fence, token rotation, revision bump);
  `resumptionContext` added to the create query; `'superseded'` added to every
  not-terminal status list (claim, lease-expiry recovery, transition, finalize, interrupt).

**factory-service — service & web**
- `.../service/AgentStepQuestionService.kt` (new, ~570 lines): the whole ask/answer
  orchestration described above.
- `.../service/DurableAgentAttemptService.kt`: `supersede` delegate.
- `.../web/AgentStepQuestionController.kt` (new): `POST /api/factory/agent-step-questions`,
  bearer-capability auth, trust-boundary identity fencing, 202 envelope.
- `.../web/AgentStepQuestionDtos.kt` (new): request/envelope/response DTOs.
- `.../workflow/service/WorkflowService.kt`: `agent_question` projection enrichment.
- `.../workflow/web/WorkflowController.kt`: dedicated human answer endpoint
  (`.../agent-step-questions/{interactionId}/answer`), human-principal enforced.

**agentos bridge plugin**
- `.../factorybridge/tools/FactoryAskStepQuestionTool.kt` (new): the worker tool — schema
  with no identity fields, deterministic `contextHash`, binding acquire/release only,
  `X-Idempotency-Key` = context hash.
- `.../factorybridge/FactoryToolPlugin.kt`: tool registration.
- `.../factorybridge/FactoryToolGrantPolicy.kt`: `FACTORY__ask_step_question` authorised
  against the same case-scoped capability as `submit_step_result`.
- `.../factorybridge/FactoryToolGrantService.kt`: `ask_step_question` grantable only as an
  explicit worker capability (never implied).

**Spec**: `specs/90701489_ask_step_question_resumption.md` — recon findings, design choices
and invariants.

## Tests / verification

New and updated tests under `factory-service/src/test/.../agentattempt/` and the bridge
plugin cover every acceptance criterion:

- `domain/StepQuestionValidationTest.kt` — schema bounds, type/option rules, unknown fields.
- `domain/AgentAttemptStateMachineTest.kt` — `superseded` terminal, non-success, reachable
  only from `waiting_human`.
- `AgentStepQuestionServiceTest.kt` — ask path: same-operation transition + interaction,
  idempotent re-ask, already-asked rejection, non-running rejection, capability and identity
  fencing, expiry.
- `AgentStepQuestionAnswerServiceTest.kt` — answer path: supersede + N+1 registration with
  bounded context, CAS-close with audited actor, double-answer rejected without N+2, stale
  revision / lost CAS conflicts.
- `AgentStepQuestionServiceIntegrationTest.kt` — end-to-end against Neo4j: park → answer →
  supersede → resume, exactly once.
- `AgentStepQuestionDurabilityTest.kt` — a real Neo4j engine restart: the waiting question
  and interaction survive, and the answer still supersedes N and persists N+1.
- `AgentStepQuestionControllerHttpTest.kt` — HTTP contract: 202 + interaction link, idempotent
  replay, 400/401/404/409 mappings, human answer flow, stale-revision conflict.
- `workflow/WorkflowControllerHttpTest.kt` — updated for the new controller dependency.
- Bridge plugin: `FactoryAskStepQuestionToolSpec.kt` (tool identity, identity-free schema,
  fail-closed outside a bound case, explicit-only grant) and `FactoryGetWorkflowToolSpec.kt`
  (tool list now includes `FACTORY__ask_step_question`).

Run with the affected-tests factory command, or targeted:
`pnpm nx test factory-service` and the bridge plugin's Gradle test task.

## Invariants to preserve when touching this code

- Factory is the single authority; identities come from the capability binding /
  `TrustContext` / authenticated principal, never from model-authored strings.
- A superseded attempt is immutable; `SUCCEEDED` remains reachable only from
  `RUNNING`/`WAITING_HUMAN`.
- The question channel never redeems the single-use result capability.
- `ask_step_question` stays a WORKER-only tool.
