# Phase 7 — Workstream Agent command tools (agentos-factory-bridge-plugin)

## Goal

Let the Workstream Agent **AGIR** through governed command tools without becoming the authority.
The Factory keeps sole authority over workflow facts; every tool only *proposes/commands* and the
Factory validates under revision fences and policy. No tool ever accepts root, runtime URL, token,
arbitrary caseId or fabricated evidence from model input — all execution identity is derived from the
trusted `ToolContext`.

This is an **additive, mostly-wiring** change: the three authoritative Factory endpoints and the
PlanChangeProposal store **already exist and are fully implemented** (see Recon). Three of the six
command tools already exist; three must be created. No factory-service changes are required.

---

## Recon summary (verified against the real source)

### Tools that ALREADY exist (under `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/`)
- `FactoryStartWorkflowTool.kt` — POSTs `/api/factory/workflows/{id}/start`, sends a body `execution`
  block built from `ToolContext` (namespaceId, runtimeId, kind=agentos, agentId, caseId, actorId).
  Returns success JSON `{workflowId, revision, created, idempotent, governanceMode, definitionVersion,
  definitionHash, projection}` + metadata.
- `FactoryRequestTransitionTool.kt` — POSTs `/api/factory/workflows/{id}/transitions`, body
  `{transition:<Input>, execution:<ctx>}`. Returns success JSON = Factory `data` (`revision`,`changed`).
- `FactoryRequestHumanDecisionTool.kt` — POSTs `/api/factory/workflows/{id}/interactions` with trust
  **headers** (`x-factory-namespace-id`, `x-factory-runtime-id`, `x-factory-agent-id`,
  `x-factory-case-id`). On success it **suspends** the run via `awaiter.awaitAnswer(...)` (throws — never
  returns a success result); error/malformed paths return `ToolExecutionResult.error`. This "suspend"
  behaviour *is* the pending-human state and must be preserved.
- `FactoryTransitionWorkflowTool.kt` — legacy alias delegating to `FactoryRequestTransitionTool`.
- Read tools (Phase 6): `FactoryGetWorkflowTool`, `FactoryGetWorkstreamTool`, `FactoryListWorkflowsTool`,
  `FactoryGetStepAttemptsTool`, `FactoryGetBlockersTool`, `FactoryGetRequiredHumanActionsTool`, plus
  shared helper `FactoryReadSupport.kt`.

### Tools that DO NOT exist yet (must be created)
- `FactoryRequestAgentRetryTool.kt`
- `FactoryInterruptAttemptTool.kt`
- `FactoryProposePlanChangeTool.kt`

### Factory endpoints (all already implemented — DO NOT MODIFY factory-service)
File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`

1. **Retry** — `POST /api/factory/workflows/{workflowId}/retries` (≈ line 527).
   - Request body: strict allowlist `{namespaceId?, stepId, expectedRevision, reasonCode}`. **Rejects any
     other key** → `INVALID_RETRY_REQUEST` (400). `stepId`, `expectedRevision`, `reasonCode` are required.
   - Identity: `resolveWorkflowCaller(trustContext, …, body.namespaceId)` — scope comes from the verified
     `TrustContext`, **not** from a body `execution` block.
   - Success: `201 { "data": { "workflowId", "interaction": { "interactionId","workflowId","stepId",
     "interactionType":"retry","status":"waiting","revision" } } }`. (Service `WorkflowService.openRetry`
     opens a `retry` human interaction; only a **blocked** step can be retried, else `INTERACTION_STALE`;
     stale revision → `REVISION_CONFLICT`.)

2. **Interrupt attempt** — `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel` (≈ line 464).
   - Request body: strict allowlist `{namespaceId?, expectedRevision, reason?}`. Other keys →
     `INVALID_REQUEST`. `expectedRevision` required.
   - Identity: `resolveWorkflowCaller(trustContext, …, body.namespaceId)`.
   - Success: `{ "data": { "workflowId","attemptId","stepId","status","revision","idempotent",
     "reconciledVerdict" } }` (`status` is the attempt status dbValue, typically `interrupted`).
   - Errors: `BRIDGE_CANCELLATION_UNAVAILABLE` (503) when bridge disabled, `REVISION_CONFLICT`, attempt
     transition errors.

3. **Actions / blockers (for enrichment)** — `GET /api/factory/workflows/{workflowId}/actions?namespaceId={ns}` (≈ line 853).
   - Success: `{ "data": { "allowedActions": [ {type, interactionId?, stepId?, attemptId?, caseId?,
     questionEventId?, expectedRevision, label?} ], "blockers": [ {code, stepId?, message} ] } }`.
   - Action `type` ∈ `reply|retry|cancel_attempt|continue_cost|stop_cost`.

4. **Plan-change proposals** — `POST /api/factory/plan-change-proposals`.
   File: `factory-service/src/main/kotlin/io/whozoss/factory/planchange/web/PlanChangeProposalController.kt`
   and DTOs `.../planchange/web/PlanChangeDtos.kt`.
   - Request body `SubmitPlanChangeRequest` (strict; unknown fields → `INVALID_PLAN_CHANGE_PROPOSAL`):
     `{ workflowId, namespaceId, expectedRevision, reasonCode, summary, proposalType,
     affectedStepIds?, proposedDependencyChanges?, proposedScopeChanges?, evidenceRefs?, idempotencyKey }`.
     - `proposalType` ∈ (parsed by `PlanChangeProposalType.parse`) — see enum values in
       `planchange/domain/PlanChangeProposalType.kt`; builder must read it and expose the exact wire
       values in the tool schema (e.g. new-step / dependency / scope / contract-or-oracle / path).
     - `proposedDependencyChanges[]` = `{op (ADD|REMOVE), fromStepId, toStepId}`.
     - `proposedScopeChanges[]` = `{op (EXPAND|REDUCE|MODIFY), target, detail?}`.
     - Bounds in `PlanChangeBounds` (summary ≤2000, affectedStepIds ≤100, idempotencyKey ≤128, etc.).
   - Identity: `resolveFactoryCaller(trustContext, …)` — scope + `actorId` from the verified `TrustContext`
     (`actorId = principalId ?: "factory-operator"`); `namespaceId` is taken from the **body** and
     validated. **Never** reads identity from a model-authored field.
   - Success: `201 Created` (or `200 OK` when idempotent) `{ "data": PlanChangeProposalResponse }` where
     `PlanChangeProposalResponse = { proposalId, workflowId, namespaceId, workstreamId, reasonCode,
     summary, proposalType, kind, recommendedVerdict, status, expectedRevision, affectedStepIds,
     proposedDependencyChanges, proposedScopeChanges?, evidenceRefs, revision, idempotent, createdAt,
     updatedAt, decisions[] }`.
   - Errors: `INVALID_PLAN_CHANGE_PROPOSAL` (400), `INVALID_NAMESPACE_ID` (400),
     `PLAN_CHANGE_GATE_REQUIRED` (409 on `/decide`, not on submit), `TRUST_CONTEXT_UNAVAILABLE` (401).

### Trust / identity model (critical)
- `TrustContextExtractor` (`factory-service/.../web/TrustContextExtractor.kt`) builds the `TrustContext` from
  (1) JWT, (2) signed proxy headers, or (3) **loopback-dev** mode, which reads `x-factory-actor-id` →
  `principalId`, `x-factory-namespace-id` → `namespaceId`, `x-factory-case-id` → `caseId`.
- The bridge runs on localhost against the Factory; the existing `FactoryRequestHumanDecisionTool` and
  `FactoryCheckpointClient` already pass identity via `x-factory-*` headers. **New command tools MUST
  mirror this**: send `x-factory-namespace-id`, `x-factory-runtime-id`, `x-factory-agent-id`,
  `x-factory-case-id`, and `x-factory-actor-id`, all sourced from `ToolContext`.
- Every tool fails closed before any HTTP call when the trusted context is incomplete, using the stable
  bridge codes already in use:
  - no single controlling case → `CASE_CONTEXT_UNAVAILABLE`
  - missing agent identity → `AGENT_CONTEXT_UNAVAILABLE`
  - missing actor identity → `USER_CONTEXT_UNAVAILABLE`

### SDK contracts (`agentos/agentos-sdk/.../tool/`)
- `ToolContext(namespaceId: UUID, userId: UUID?, userExternalId: String?, caseEvents: List<CaseEvent>,
  agentName: String? = null, credentialProvider = null, toolRequestId = null)`.
  Actor resolution precedent (from existing tools): `context.userExternalId?.ifBlank-null ?:
  context.userId?.toString()`.
- `StandardTool<Input>` requires: `name`, `description`, `version`, `paramType: Class<Input>`,
  `inputSchema: String` (JSON schema string), `suspend fun execute(input: Input?, context: ToolContext):
  ToolExecutionResult`.
- `ToolExecutionResult.success(output: String, metadata: Map<String,Any?> = emptyMap(), structuredOutput:
  JsonNode? = null)` and `ToolExecutionResult.error(output: String, errorType: String? = null,
  errorMessage: String? = null)`.

### Plugin wiring
- `FactoryToolPlugin.kt` → `buildFactoryTools(services)` instantiates every tool (one list, shared by the
  plugin and the grant service). Tools take `(baseUrl, httpClient, objectMapper[, runtimeId])` from
  `FactoryBridgeServices`.
- `FactoryToolGrantService.kt` maps integration-allowlist suffixes → tool names (explicit-only; empty/absent
  allowlist grants nothing). Must list every grantable command suffix.

### Tests
- Framework: **Kotest `StringSpec`** + `com.sun.net.httpserver.HttpServer` for a fake Factory + real
  `OkHttpClient()`. Fixtures: `FactoryTestFixtures` (`services()`, `tools()`, `grantService()`).
  Existing specs live directly under `src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/`
  (NOT in a `tools/` subpackage). The new test file in the claims list is placed in the `tools/`
  subpackage path and must declare `package io.whozoss.agentos.plugins.factorybridge.tools`.

---

## Standardized command-tool output contract (AC2)

Every command tool, on its **success/return** path, returns `ToolExecutionResult.success(output)` where
`output` is a JSON object with this exact shape:

```jsonc
{
  "status": "accepted" | "rejected" | "pending-human",
  "revision": <int|null>,          // current workflow/attempt/proposal revision from the Factory response
  "reasonCode": <string|null>,     // machine code (e.g. recommendedVerdict, "retry_requested", null when none)
  "interactionId": <string|null>,  // when an interaction was created (retry)
  "proposalId": <string|null>,     // when a proposal was created (plan-change)
  "allowedActions": [ ... ],       // from GET /{workflowId}/actions, best-effort; [] if unavailable
  "message": <string|null>         // short human-readable summary
}
```

Rules:
- **Rejected / transport failures** keep the existing idiom: `ToolExecutionResult.error(message,
  errorType = <stable code>, errorMessage = message)`. `errorType` carries the Factory `error.code`
  (verbatim when present) or a stable bridge code (`FACTORY_UNAVAILABLE`, `FACTORY_TIMEOUT`,
  `MALFORMED_FACTORY_RESPONSE`, `FACTORY_REQUEST_FAILED`, `CASE_CONTEXT_UNAVAILABLE`,
  `AGENT_CONTEXT_UNAVAILABLE`, `USER_CONTEXT_UNAVAILABLE`). This matches `FactoryReadSupport.errorResult`.
- `status` mapping:
  - `start_workflow` → `accepted` (created or idempotent).
  - `request_transition` → `accepted` (policy applied; changed or idempotent).
  - `request_agent_retry` → `pending-human` (opens a `retry` interaction awaiting human approval).
  - `interrupt_attempt` → `accepted` (attempt moved to `interrupted`).
  - `propose_plan_change` → derive from response `status`/`recommendedVerdict`: `AUTO_APPLIED` → `accepted`;
    `GATE_REQUIRED` / `REQUIRES_NEW_DEFINITION` → `pending-human`; `REJECTED` → `rejected`
    (return via `success` with `status:"rejected"` since the HTTP call itself succeeded).
  - `request_human_decision` → inherently `pending-human` (suspends; no return). Leave as-is.
- **`allowedActions` enrichment**: after a successful command that has a `workflowId`, best-effort
  `GET /api/factory/workflows/{workflowId}/actions?namespaceId={ns}` and copy `data.allowedActions`.
  Any non-2xx / transport error / parse error → `allowedActions: []` (never fail the command because of it).

> To respect the exact build-gate claims list, **do not create a new shared helper source file**. Put the
> small standardized-output builder + best-effort actions fetch as `private` functions inside each command
> tool file. Duplication across the tool files is acceptable and keeps the claimed file set exact.

---

## File-by-file changes

### 1. CREATE `tools/FactoryRequestAgentRetryTool.kt`
`package io.whozoss.agentos.plugins.factorybridge.tools`

- Class `FactoryRequestAgentRetryTool(baseUrl, httpClient, objectMapper, runtimeId) : StandardTool<Input>`.
- `data class Input(workflowId, stepId, expectedRevision: Long, reasonCode, idempotencyKey: String? = null)`.
- `name = "FACTORY__request_agent_retry"`, `version = "1.0.0"`, description making clear it only *requests* a
  retry (subject to budgets & policy); the agent cannot pick an arbitrary caseId or capability.
- `inputSchema` (strict, `additionalProperties:false`), mirroring contract §6.2:
  `{workflowId ≤128, stepId ≤128, expectedRevision ≥1, reasonCode ≤64, idempotencyKey ≤128}`,
  `required: [workflowId, stepId, expectedRevision, reasonCode]`. **No identity fields.**
- `execute`:
  1. Null/validation guards; resolve `caseId` (single controlling case or `CASE_CONTEXT_UNAVAILABLE`),
     `agent` (`AGENT_CONTEXT_UNAVAILABLE`), `actor` (`USER_CONTEXT_UNAVAILABLE`).
  2. Build body = exactly `{namespaceId, stepId, expectedRevision, reasonCode}` (the endpoint rejects
     unknown keys — do **not** send `idempotencyKey` in the body; it is not an accepted key). `namespaceId`
     from `context.namespaceId`.
  3. POST `/api/factory/workflows/{urlEncoded(workflowId)}/retries` with trust headers
     (`x-factory-namespace-id`, `x-factory-runtime-id`=runtimeId, `x-factory-agent-id`=agent,
     `x-factory-case-id`=caseId, `x-factory-actor-id`=actor) on `Dispatchers.IO`.
  4. On non-2xx → `error` carrying Factory `error.code`/`error.message` (fallback `FACTORY_REQUEST_FAILED`).
     On parse failure → `MALFORMED_FACTORY_RESPONSE`. On transport failure → `FACTORY_UNAVAILABLE`
     (and `FACTORY_TIMEOUT` on `SocketTimeoutException`).
  5. On success parse `data.interaction` → `interactionId`, `revision`. Best-effort fetch `allowedActions`.
     Return standardized `success` JSON with `status:"pending-human"`, `reasonCode:"retry_requested"`,
     `interactionId`, `revision`.
- `private fun fail(code,message)` helper mirroring the existing tools.

### 2. CREATE `tools/FactoryInterruptAttemptTool.kt`
- Class `FactoryInterruptAttemptTool(baseUrl, httpClient, objectMapper, runtimeId) : StandardTool<Input>`.
- `data class Input(workflowId, attemptId, expectedRevision: Long, reason: String? = null, idempotencyKey:
  String? = null)`.
- `name = "FACTORY__interrupt_attempt"`.
- `inputSchema` strict per §6.2: `{workflowId ≤128, attemptId ≤128, expectedRevision ≥1, reason ≤500,
  idempotencyKey ≤128}`, `required: [workflowId, attemptId, expectedRevision]`.
- `execute`:
  1. Same context guards (case/agent/actor).
  2. Body = exactly `{namespaceId, expectedRevision, reason?}` (endpoint allowlist is
     `namespaceId|expectedRevision|reason`; omit `reason` key when null; **do not** send `idempotencyKey`
     or `attemptId` in the body — `attemptId` is a path variable).
  3. POST `/api/factory/workflows/{wf}/attempts/{urlEncoded(attemptId)}/cancel` with the same trust headers.
  4. Error mapping as above (plus surface `BRIDGE_CANCELLATION_UNAVAILABLE` verbatim from the 503 envelope).
  5. On success parse `{workflowId, attemptId, stepId, status, revision, idempotent, reconciledVerdict}`.
     Best-effort `allowedActions`. Return standardized `success` with `status:"accepted"`,
     `reasonCode = status` (e.g. `interrupted`), `revision`, and include `attemptId`/`stepId`/
     `reconciledVerdict`/`idempotent` in the output JSON too.

### 3. CREATE `tools/FactoryProposePlanChangeTool.kt`
- Class `FactoryProposePlanChangeTool(baseUrl, httpClient, objectMapper, runtimeId) : StandardTool<Input>`.
- Nested DTOs mirroring the controller:
  `data class DependencyChange(op, fromStepId, toStepId)`,
  `data class ScopeChange(op, target, detail: String? = null)`,
  `data class Input(workflowId, expectedRevision: Long, reasonCode, summary, proposalType,
   affectedStepIds: List<String>? = null, proposedDependencyChanges: List<DependencyChange>? = null,
   proposedScopeChanges: List<ScopeChange>? = null, evidenceRefs: List<String>? = null, idempotencyKey)`.
- `name = "FACTORY__propose_plan_change"`; description stresses **append-only** / proposes only, Factory
  validates, classifies and gates; the agent never applies a plan change.
- `inputSchema` strict, bounds from `PlanChangeBounds` (summary ≤2000, affectedStepIds ≤100 unique,
  dependency/scope changes ≤50, evidenceRefs ≤100 (≤512 each), reasonCode ≤64, idempotencyKey ≤128,
  workflowId ≤128). `proposalType` enum must list the exact `PlanChangeProposalType` wire values —
  **builder reads `planchange/domain/PlanChangeProposalType.kt` to get them**. `required:
  [workflowId, expectedRevision, reasonCode, summary, proposalType, idempotencyKey]`.
  Do **not** expose `namespaceId` in the schema — inject it from `ToolContext`.
- `execute`:
  1. Context guards (case/agent/actor).
  2. Body = `SubmitPlanChangeRequest` shape with `namespaceId = context.namespaceId.toString()` injected,
     plus the model-authored content fields. Send trust headers (`x-factory-namespace-id`,
     `x-factory-runtime-id`, `x-factory-agent-id`, `x-factory-case-id`, `x-factory-actor-id`).
  3. POST `/api/factory/plan-change-proposals`.
  4. Error mapping as above (`INVALID_PLAN_CHANGE_PROPOSAL`, `INVALID_NAMESPACE_ID`,
     `TRUST_CONTEXT_UNAVAILABLE`, transport codes).
  5. On success (201 or 200) parse `data` → `proposalId`, `revision`, `status`, `recommendedVerdict`,
     `idempotent`. Map `status` per the standardized rules; `reasonCode = recommendedVerdict`.
     Best-effort `allowedActions` via `GET …/{workflowId}/actions`. Return standardized `success` JSON
     with `proposalId`.

### 4. UPDATE `tools/FactoryStartWorkflowTool.kt`
- Keep the existing body/execution + response parsing (do not break the `start` endpoint contract).
- In `parseResponse`, build and return the **standardized** `success` output JSON
  (`status:"accepted"`, `revision`, `reasonCode:null`, `proposalId:null`, `interactionId:null`,
  `allowedActions` best-effort, `message`) **while preserving** the metadata keys the existing spec asserts
  (`created`, `idempotent`, `governanceMode`, `definitionVersion`, `definitionHash`). Keep returning the
  rich fields inside the output object too. Because `allowedActions` enrichment needs an extra HTTP call,
  make it a best-effort call inside `execute` after a successful `parseResponse` (not inside the pure
  `parseResponse`, so the existing `parseResponse` unit assertions still hold); merge `allowedActions:[]`
  by default.
- Note: the current `start` tool does not send trust headers (identity travels in the body `execution`
  block, which the `start` endpoint reads for namespace). Leave that as-is; only standardize the output.

### 5. UPDATE `tools/FactoryRequestTransitionTool.kt`
- Keep the body `{transition, execution}` and the Factory `data` validation.
- Replace the raw `success(data)` return with the standardized output: `status:"accepted"`,
  `revision = data.revision`, `reasonCode:null`, `message`, best-effort `allowedActions` via
  `GET …/{workflowId}/actions`. Preserve the `changed` field inside the output JSON.
- Keep all error codes (`PASS_EVIDENCE_REQUIRED`, `MALFORMED_FACTORY_RESPONSE`, etc.).

### 6. KEEP `tools/FactoryRequestHumanDecisionTool.kt` behaviour
- Its success path suspends (pending-human) and must not change. Optionally refine the error/`message`
  text, but **do not** alter the suspend contract or the headers it sends. No standardized success JSON is
  returned (there is no return on success — it throws to suspend). Listed as "updated" only if a trivial
  doc/`message` touch is needed; functionally unchanged.

### 7. UPDATE `FactoryToolPlugin.kt` (`buildFactoryTools`)
- Add imports and instantiate the three new tools in the returned list:
  ```kotlin
  FactoryRequestAgentRetryTool(baseUrl, httpClient, objectMapper, runtimeId),
  FactoryInterruptAttemptTool(baseUrl, httpClient, objectMapper, runtimeId),
  FactoryProposePlanChangeTool(baseUrl, httpClient, objectMapper, runtimeId),
  ```

### 8. UPDATE `FactoryToolGrantService.kt`
- Add the three new suffixes to the `grantedSuffixes` `when` mapping (both bare and `FACTORY__`-prefixed):
  ```kotlin
  "request_agent_retry", "FACTORY__request_agent_retry" -> "request_agent_retry"
  "interrupt_attempt",   "FACTORY__interrupt_attempt"   -> "interrupt_attempt"
  "propose_plan_change", "FACTORY__propose_plan_change" -> "propose_plan_change"
  ```
- Do **NOT** remove existing mappings. Worker tools (`submit_step_result`, `ask_step_question`) remain in
  the mapping for worker personas; the Workstream Agent persona simply must not list them in its allowlist.
- Fix/avoid the misleading default argument: the method default
  `integrations = mapOf(FACTORY to listOf("publish_projection"))` is only a convenience default and does
  not force a grant. Leave it unless a test needs otherwise; the grant stays explicit-only.
- Per §5 capability matrix, document (comment) which suffixes the **Workstream Agent** persona may be
  granted: `get_*` reads, `request_human_decision`, `propose_plan_change`, `request_agent_retry`. It must
  **not** be granted `start_workflow`, `request_transition`, `interrupt_attempt`, `submit_step_result`,
  `ask_step_question`, `record_*`, `provision_environment`, `publish_projection`.

### 9. CREATE `src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryCommandToolsTest.kt`
`package io.whozoss.agentos.plugins.factorybridge.tools` — Kotest `StringSpec`, fake Factory via
`com.sun.net.httpserver.HttpServer`, real `OkHttpClient()`, `jacksonObjectMapper()`. Mirror
`FactoryRequestTransitionToolSpec` structure. Build a `ToolContext` with a single `CaseStatusEvent`
(import `io.whozoss.agentos.plugins.factorybridge.FactoryTestFixtures` for the grant service — it is
`internal`, so this test being in the same Gradle module/source set has access; the test class is in the
`tools` subpackage but same module). Cover:
- **Schema strictness** for each new tool: `additionalProperties:false`, exact property set, no
  `namespaceId`/`agentId`/`caseId`/`requestId`/token fields.
- **request_agent_retry**: fake server returns `201 {"data":{"workflowId":"wf-1","interaction":
  {"interactionId":"retry-1","workflowId":"wf-1","stepId":"build","interactionType":"retry",
  "status":"waiting","revision":5}}}`; assert the posted path is `/api/factory/workflows/wf-1/retries`,
  the body keys are exactly `{namespaceId, stepId, expectedRevision, reasonCode}`, the trust headers are
  present and sourced from the context, and the returned output JSON has `status:"pending-human"`,
  `interactionId:"retry-1"`, `revision:5`, `reasonCode:"retry_requested"`, `allowedActions` present.
  Serve `/actions` on the same server so enrichment is exercised. Also assert the three context-guard
  failures (`CASE_CONTEXT_UNAVAILABLE`, `AGENT_CONTEXT_UNAVAILABLE`, `USER_CONTEXT_UNAVAILABLE`) and a
  Factory 409 `REVISION_CONFLICT`/`INTERACTION_STALE` propagation.
- **interrupt_attempt**: server returns `{"data":{"workflowId":"wf-1","attemptId":"att-1","stepId":"build",
  "status":"interrupted","revision":6,"idempotent":false,"reconciledVerdict":"failed"}}`; assert path
  `/api/factory/workflows/wf-1/attempts/att-1/cancel`, body keys ⊆ `{namespaceId, expectedRevision, reason}`,
  output `status:"accepted"`, `revision:6`, `reasonCode:"interrupted"`; a 503
  `BRIDGE_CANCELLATION_UNAVAILABLE` propagation.
- **propose_plan_change**: server returns `201 {"data":{"proposalId":"p-1","workflowId":"wf-1",
  "revision":1,"status":"GATE_REQUIRED","recommendedVerdict":"GATE_REQUIRED","idempotent":false, …}}`;
  assert path `/api/factory/plan-change-proposals`, body carries injected `namespaceId` and the content
  fields, output `status:"pending-human"`, `proposalId:"p-1"`, `reasonCode:"GATE_REQUIRED"`; a 400
  `INVALID_PLAN_CHANGE_PROPOSAL` propagation and an idempotent `200` → still `success`.
- **Grant service**: using `FactoryTestFixtures.grantService()`, assert
  `grantTools(ctx, mapOf("FACTORY" to listOf("request_agent_retry","interrupt_attempt",
  "propose_plan_change")))` returns exactly those three tool names; a Workstream-Agent-style allowlist
  (`listOf("get_workflow","get_blockers","request_human_decision","propose_plan_change",
  "request_agent_retry")`) does **not** include `submit_step_result`/`ask_step_question`/`start_workflow`/
  `request_transition`/`interrupt_attempt`; absent/empty allowlist grants nothing; unknown suffix grants
  nothing.
- If the output-shape change to `start`/`request_transition` breaks their existing specs
  (`FactoryStartWorkflowToolSpec.kt`, `FactoryRequestTransitionToolSpec.kt`), **update those spec files**
  to assert the new standardized output (keep assertions on preserved fields like `created`/`idempotent`/
  `changed`). This is expected and in-scope even though those spec files are not in the headline claims
  list.

### 10. UPDATE `app_docs/workstream_agent_cartography_and_contracts.md`
- In §2.1 tools table, mark `FACTORY__request_human_decision`, `request_transition`, `start_workflow` as
  implemented and **add rows** for `FACTORY__request_agent_retry` → `POST …/retries`,
  `FACTORY__interrupt_attempt` → `POST …/attempts/{attemptId}/cancel`, `FACTORY__propose_plan_change` →
  `POST /api/factory/plan-change-proposals`.
- Add a short "Phase 7 — command tools implemented" note documenting the standardized output contract
  (status / revision / reasonCode / interactionId|proposalId / allowedActions) and the per-tool §5
  capability-matrix grants for the Workstream Agent (reads + `request_human_decision` +
  `propose_plan_change` + `request_agent_retry`; never worker/authority tools).
- Keep the document consistent with §6.2 schemas; where a tool schema deviates from §6.2 to match the real
  controller (e.g. `propose_plan_change` uses `proposalType` + `proposedDependencyChanges`/
  `proposedScopeChanges` instead of a generic `operations[]`; `request_agent_retry` has no body
  `idempotencyKey`), update §6.2 to reflect the shipped contract.

---

## Boundaries / exclusions (hard constraints)
- **Do NOT modify `factory-service/.../agentattempt/`** (Phase 10 area). The cancel endpoint already exists
  in `WorkflowController` and `BridgeCancellationService`; call it, do not change it.
- **Do NOT modify any factory-service controller/service/DTO** — the three endpoints and the PlanChange
  store are complete. This is a bridge-plugin + doc change only.
- **No database migrations, no release-pipeline changes.**
- **No worker tools for the Workstream Agent**: never add `submit_step_result` / `ask_step_question` to a
  Workstream Agent allowlist; keep them capability-gated by `FactoryToolGrantPolicy` (unchanged).
- No tool accepts root, runtime URL, token, arbitrary `caseId`, `namespaceId`, `agentId`, `actorId` or a
  fabricated evidence id from model input. Those are injected from `ToolContext`; only bounded
  `workflowId`/`stepId`/`attemptId` and the explicit command payload come from the model.

---

## Verification

Build-gate claims (exact files created/modified — declare these):
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryToolPlugin.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryToolGrantService.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryStartWorkflowTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryRequestTransitionTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryRequestHumanDecisionTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryRequestAgentRetryTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryInterruptAttemptTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryProposePlanChangeTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryCommandToolsTest.kt`
- `app_docs/workstream_agent_cartography_and_contracts.md`

(If the output-shape change forces edits to `FactoryStartWorkflowToolSpec.kt` /
`FactoryRequestTransitionToolSpec.kt`, those are additional, expected modifications.)

Commands (the factory runs the affected suite automatically; run locally only to debug):
- Module tests: from `agentos/agentos-factory-bridge-plugin/` run `../gradlew test`
  (rootProject `agentos-factory-bridge-plugin`), or `pnpm nx test agentos-factory-bridge-plugin`.
- Lint/build gates: `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"` and
  `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`.

Acceptance mapping:
- **AC1** — six command tools implemented/updated and wired in `buildFactoryTools` + `FactoryToolGrantService`.
- **AC2** — standardized structured responses (`status`/`revision`/`reasonCode`/`interactionId|proposalId`/
  `allowedActions`) on every command tool return path.
- **AC3** — `FactoryCommandToolsTest.kt` covers the new tools (schema, payload, trust headers, success +
  rejection + context-guard paths, enrichment) and the grant service.
- **AC4** — module test suite green.
