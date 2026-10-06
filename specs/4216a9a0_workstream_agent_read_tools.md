# Phase 6 — Read-only Workstream Agent tools & wiring

## Goal

Deliver the six **read-only** Workstream Agent tools in
`agentos/agentos-factory-bridge-plugin/`, wire them into `buildFactoryTools`, extend the
grant allowlist, add a generic Workstream Agent skill, and make the two small read-only
filter additions in `factory-service/` that the tools rely on. Zero mutation capability is
introduced; no worker/command tool is granted to the Workstream Agent.

Everything builds on **Phase 5** (`WorkstreamProjectionService` →
`GET /api/factory/workstreams/{workstreamId}/projection`) and **Phase 2**
(`DurableAgentAttemptDto` / `GET /api/factory/workflows/{workflowId}/attempts`), both of
which already exist in the tree. The tools call already-existing endpoints; the only
factory-service work is additive, read-only query-param filtering.

## Hard constraints (do not violate)

- **Read-only everywhere.** No tool may mutate. Do NOT add or grant any worker/command tool
  (`submit_step_result`, `ask_step_question`, `record_agent_result`, `record_artifact`,
  `request_transition`, `transition_workflow`, `start_workflow`, `provision_environment`,
  `request_human_decision`, `publish_projection`) to the Workstream Agent.
- Do NOT touch DB migrations, Flyway, Neo4j schema, or release-pipeline files.
- Do NOT change any mutation logic in `factory-service`. The only factory-service edits are
  additive, read-only query-parameter filters over data already returned by existing beans.
- Do NOT edit the Phase 2/5 aggregates themselves (`WorkstreamProjectionService`,
  `DurableAgentAttemptService`, their nodes/repositories). Consume them read-only.
- No tool emits preformatted Markdown; every tool returns a **bounded, structured JSON DTO**.
- Identities (`workstreamId`, `namespaceId`, `caseId`, `actorId`, `organizationId`) are
  injected from the trusted `ToolContext` / resolved server-side from `TrustContext`. The
  model input carries at most a `workflowId`/`stepId`/paging arg; never an identity.
- No semicolons / 120-col / explicit types / KDoc every public type (match the neighbours).
- Scratch output → `/tmp`, never into the repo tree.
- Build claims must declare EXACT path changes matching the git diff strictly.

## Reference patterns already in the repo (copy these)

- **Read tool shape**:
  `agentos/.../factorybridge/tools/FactoryGetWorkflowTool.kt` — `StandardTool<Input>`, bounded
  `inputSchema` JSON string, `namespaceId` injected from `ToolContext`, `parseResponse(...)`
  helper, `ToolExecutionResult.success/error`, error codes
  `FACTORY_TIMEOUT`/`FACTORY_UNAVAILABLE`/`MALFORMED_FACTORY_RESPONSE`/`FACTORY_REQUEST_FAILED`.
- **Trust-context extraction in a tool**:
  `tools/FactoryRequestTransitionTool.kt` and `tools/FactoryEnvironmentTool.kt` — single
  controlling case (`CASE_CONTEXT_UNAVAILABLE`), agent identity (`AGENT_CONTEXT_UNAVAILABLE`),
  actor identity (`USER_CONTEXT_UNAVAILABLE`), `x-factory-*` headers.
- **Tool wiring + grant filter**: `FactoryToolPlugin.kt` (`buildFactoryTools`) and
  `FactoryToolGrantService.kt` (`grantedSuffixes` map, explicit allowlist).
- **Test harness**: `src/test/.../FactoryGetWorkflowToolSpec.kt`,
  `FactoryRequestTransitionToolSpec.kt`, `FactoryTestFixtures.kt` — Kotest `StringSpec`,
  `com.sun.net.httpserver.HttpServer` stub, `ToolContext(namespace, userId, externalId,
  caseEvents, agentName)`.
- **Factory-side DTOs**:
  - `factory-service/.../workstream/web/WorkstreamDtos.kt` (`WorkstreamProjectionResponse`).
  - `factory-service/.../agentattempt/domain/DurableAgentAttemptDto.kt`.
  - `factory-service/.../workflow/domain/WorkflowActionsModels.kt`
    (`AllowedActionDto`, `WorkflowBlockerDto`, `WorkflowActionsResponseDto`,
    `WorkflowBlockerCodes`, `WorkflowActionTypes`).
- **Factory-side controllers**: `WorkflowController.kt` (`list`, `get`, `listAttempts`,
  `actions`, `listInteractions`), `WorkstreamController.kt` (`projection`).
- **Contracts of record**: `app_docs/workstream_agent_cartography_and_contracts.md` §6.1 & §7,
  `app_docs/f3c4bd0f_workstream-aggregate-projection.md`,
  `docs/adr/0001-workstream-agent-factory-worker-separation.md`.

## Endpoint mapping (all already exist)

| Tool | Endpoint called | Factory bean behind it |
|---|---|---|
| `FACTORY__get_workstream` | `GET /api/factory/workstreams/{workstreamId}/projection?namespaceId=&limit=` | `WorkstreamProjectionService` (Phase 5) |
| `FACTORY__list_workflows` | `GET /api/factory/workflows?namespaceId=&state=&workflowType=&limit=` | `WorkflowService.listProjections` |
| `FACTORY__get_workflow` | `GET /api/factory/workflows/{workflowId}?namespaceId=` + `GET /api/factory/workflows/{workflowId}/actions?namespaceId=` | `WorkflowService.getProjection` + `workflowActions` |
| `FACTORY__get_step_attempts` | `GET /api/factory/workflows/{workflowId}/attempts?namespaceId=&stepId=` | `DurableAgentAttemptService.findByWorkflow` |
| `FACTORY__get_blockers` | `GET /api/factory/workflows/{workflowId}/actions?namespaceId=` | `WorkflowService.workflowActions` |
| `FACTORY__get_required_human_actions` | `GET /api/factory/workflows/{workflowId}/actions?namespaceId=` | `WorkflowService.workflowActions` (allowedActions of type `reply`) |

`workstreamId`/`organizationId` are resolved server-side from the trusted `TrustContext`
(membership) and boundary-checked by the Factory (`assertWithinWorkstream` → 403
`WORKSTREAM_BOUNDARY_VIOLATION`). The bridge therefore passes **only** `namespaceId`
(from `ToolContext`) and the model's `workflowId`/`stepId`/paging args; it never sends a
`workstreamId`/`organizationId` from model input. For `get_workstream` the tool passes the
model-supplied `workstreamId` **in the path only** and lets the Factory enforce the boundary
(a path id outside the trusted scope → Factory 403, surfaced as the stable error code).

---

## Part A — factory-service (minimal, additive, read-only)

Two read-only filter params so the step-attempts and workflow-list tools can be
server-filtered and bounded. Both operate on data already returned by existing beans; no
repository/Cypher/service-mutation change.

### A1. `WorkflowController.list` — optional `workflowType` + `limit` filters

File: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`
(method `list`, around line 90) and
`factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
(method `listProjections`, line 165).

- Add two optional `@RequestParam` to `list`: `workflowType: String?` and `limit: Int?`.
- Overload / extend `WorkflowService.listProjections` with optional
  `workflowType: String? = null` and `limit: Int? = null` applied **after** the existing
  `publicSnapshot` mapping, over the in-memory list only:
  - Filter items whose projected `workflowType` equals `workflowType` (read the type from the
    snapshot's `projection.workflowType`; drop items without a type when a filter is given).
  - Keep deterministic ordering (the repo order; do not re-sort beyond what exists).
  - Coerce `limit` into `[1, 200]` (contract bound) and take the first N; add
    `"truncated": Boolean` and keep existing `"items"`/`"namespaceId"`/`"state"` keys.
  - Leave behaviour **identical** when both params are null (existing tests must stay green).
- Keep the `state` validation untouched (`active`/`removed`). Do NOT widen it.
- Errors keep the standard envelope.

### A2. `WorkflowController.listAttempts` — optional `stepId` filter

File: `WorkflowController.kt` (method `listAttempts`, around line 437).

- Add optional `@RequestParam stepId: String?`.
- After `durableAgentAttemptService.findByWorkflow(...)`, when `stepId` is non-blank, filter
  the resulting `DurableAgentAttemptDto` list to `it.stepId == stepId` **in the controller**
  (pure in-memory read filter). Empty result → `{ "data": [] }` 200 (never an error), as the
  endpoint already does for unknown workflows.

> Rationale: `DurableAgentAttemptDto` is already secret-free (no `ownerToken`,
> `capabilityToken`, `commandId`, `brief`, `leaseExpiresAt`, `lastObservedEventId`,
> `turnCorrelation`), satisfying "NO raw LLM prose / no secrets by default".

### A3. factory-service tests

Extend the existing controller HTTP test for workflows (find it first):

```
grep -rln "class .*WorkflowController.*Test\|/attempts\|workflowType" factory-service/src/test
```

Likely `factory-service/src/test/kotlin/io/whozoss/factory/workflow/.../WorkflowController*Test.kt`
(and the attempts test from spec `73464c47`). Add cases:
- `list` with `workflowType=X` returns only matching workflows; with no filter returns all
  (regression); `limit` coerces and sets `truncated` correctly; out-of-range limit coerced.
- `listAttempts` with `stepId=Y` returns only that step's attempts; unknown step → `[]` 200;
  no `stepId` → all attempts (regression).
- Confirm the responses still carry no secret fields (assert absence of `ownerToken` etc.).

Place new assertions in the existing test classes; create a new
`WorkflowReadFilterHttpTest.kt` under
`factory-service/src/test/kotlin/io/whozoss/factory/workflow/web/` only if the existing class
is awkward to extend. Prefer extending.

---

## Part B — bridge plugin: six read tools

All under
`agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/`.
Each is a `StandardTool<Input>`, config-less, pulls `namespaceId` from `ToolContext`, URL-encodes
the path id (`URLEncoder.encode(id, UTF_8).replace("+","%20")`), runs the HTTP call on
`Dispatchers.IO`, and maps failures to the stable transport codes
(`FACTORY_TIMEOUT`, `FACTORY_UNAVAILABLE`, `MALFORMED_FACTORY_RESPONSE`,
`FACTORY_REQUEST_FAILED` — the latter two carry the Factory `error.code`/`error.message` when
present). Validation rejects out-of-pattern/overlong ids before any network call. Every output
is `objectMapper.writeValueAsString(boundedMap/list)` with `ToolExecutionResult.success`.

Common id validators (copy from `FactoryGetWorkflowTool`):
`WORKFLOW_ID = ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`;
`SAFE_ID = ^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$` for stepId/workstreamId (`maxLength` 128).

### B1. `FactoryGetWorkstreamTool.kt` — `FACTORY__get_workstream`

- `Input(workstreamId: String)`; schema: `{additionalProperties:false, properties:{workstreamId:{type:string,maxLength:128}}, required:[workstreamId]}`.
- Validate `workstreamId` non-blank, `maxLength<=128`, matches a slug-ish `SAFE_ID`; else
  `INVALID_WORKSTREAM_SLUG`.
- GET `/api/factory/workstreams/{workstreamId}/projection?namespaceId={ctx.namespaceId}`.
  (Phase 5 response is NOT enveloped in `{data:...}` — it is the raw
  `WorkstreamProjectionResponse` body; confirm by reading
  `WorkstreamController.projection`. Parse accordingly: top-level object, not `.path("data")`.)
- Return a **bounded** projection: identity (`workstreamId`, `namespaceId`, `status`,
  `workstreamRevision`), active-workflow counts/items, aggregated step counts
  (`running`/`waitingHuman`/`blocked`), `humanActions` (pending human decisions),
  and the main blockers derived from `failedOracles` + `steps.blocked` + `humanActions`.
  Carry each section's `count`/`truncated` through verbatim; do not re-expand.
- A Factory 403 (`WORKSTREAM_BOUNDARY_VIOLATION`) is surfaced as a `failure` with that exact
  code — this is the "reject any identifier out of trusted scope" path.

### B2. `FactoryListWorkflowsTool.kt` — `FACTORY__list_workflows`

- `Input(state: String? = null, workflowType: String? = null, limit: Int? = null, cursor: String? = null)`.
- Schema (contract §6.1): `state ∈ {active, removed, purged, all}` optional;
  `workflowType` optional `maxLength` 128; `limit` integer `[1,200]`; `cursor` string
  `maxLength` 256; `additionalProperties:false`, `required:[]`.
- **Mandatory pagination, enforced in the tool**: default `limit=50` when absent; coerce to
  `[1,200]`; interpret `cursor` as an opaque base-ish offset token (an integer offset encoded
  as a string; validate it parses to a non-negative int, else `INVALID_REQUEST`).
- Map `state`: `all` → call with no `state` filter wouldn't work (endpoint only accepts
  `active`/`removed`). So: `active`→`state=active`, `removed`→`state=removed`,
  `purged`/`all`→ fetch `active` (document that `purged` lifecycle is not list-exposed; map
  `all` to `active` as the supported bounded view). Pass `workflowType` and `limit` through to
  the A1 query params; apply `cursor` offset client-side over the returned bounded list.
- GET `/api/factory/workflows?namespaceId={ns}&state={s}&workflowType={t}&limit={n}` (omit
  `namespaceId` when `ctx.namespaceId` is the always-present UUID — it is always present, so
  always send it; the endpoint treats it as an optional filter).
- Output (contract §6.1): `{ items: [{workflowId, workflowType, title, status, revision}], nextCursor: string|null }`.
  Build `items` from the snapshot list (`workflowId`, `projection.workflowType`,
  `projection.title`, `projection.status`, `revision`). Compute `nextCursor` = string offset
  when more items remain after the window, else `null`.

### B3. `FactoryGetWorkflowReadTool.kt` — `FACTORY__get_workflow`

> Name the class `FactoryGetWorkflowReadTool` to avoid clashing with the existing
> `FactoryGetWorkflowTool` (whose wire name is also `FACTORY__get_workflow`). **Decision
> point for the builder:** the existing `FactoryGetWorkflowTool` already owns wire name
> `FACTORY__get_workflow` and returns projection+state. Phase 6 needs the richer read
> (current revision, steps, `allowedActions` calculated by Factory, attempts & summarized
> evidence). **Preferred approach: extend the existing `FactoryGetWorkflowTool`** to also
> fetch `/{workflowId}/actions` and merge `allowedActions`/`blockers`, plus a bounded
> attempts summary and a bounded evidence summary — rather than register a second tool with
> the same wire name (two tools with identical names would break grant filtering and the
> `provideTools` exact-list test). Keep the existing output keys and ADD
> `allowedActions`, `blockers`, `attempts` (bounded), `evidence` (bounded, summarized).
> Update `FactoryGetWorkflowToolSpec` accordingly.

- Keep `Input(workflowId)` and the existing schema.
- After the existing projection parse (state `existing`), make a second GET to
  `/api/factory/workflows/{workflowId}/actions?namespaceId={ns}`; merge its
  `allowedActions` + `blockers` (from `WorkflowActionsResponseDto`) into the output.
  `allowedActions` is **calculated by the Factory** and returned verbatim (never recomputed).
- Add a bounded `attempts` summary: GET `/{workflowId}/attempts?namespaceId={ns}`, map to at
  most N (e.g. 50) `{attemptId, stepId, attemptNumber, agentName, status, caseId,
  failureCode, resultEvidenceId, revision, createdAt, startedAt, completedAt}` — i.e. the
  secret-free `DurableAgentAttemptDto` fields.
- Add a bounded, **summarized** `evidence`: GET `/{workflowId}/evidence?namespaceId={ns}`,
  keep only refs/metadata (`evidenceId`/`id`, `stepId`, `type`/`kind`, `createdAt`) capped at
  N; **NO raw LLM prose** — drop any free-text body fields.
- Non-`existing` states keep the current minimal `{state, workflowId}` output.
- Degrade gracefully: if the `actions`/`attempts`/`evidence` sub-calls fail transport-wise,
  return the projection with empty sub-sections rather than failing the whole read (document
  this; keep the primary projection authoritative). A malformed primary projection still
  returns `MALFORMED_FACTORY_RESPONSE`.

### B4. `FactoryGetStepAttemptsTool.kt` — `FACTORY__get_step_attempts`

- `Input(workflowId: String, stepId: String)`; schema per §6.1 (`workflowId`/`stepId`
  `maxLength` 128, both required, `additionalProperties:false`).
- Validate both ids; `INVALID_WORKFLOW_ID` / `UNKNOWN_STEP`-style validation
  (`INVALID_REQUEST` for a blank/overlong `stepId`).
- GET `/api/factory/workflows/{workflowId}/attempts?namespaceId={ns}&stepId={stepId}` (A2
  server filter).
- Output (contract §6.1): JSON **array** of
  `{attemptId, stepId, attemptNumber, agentName, status, caseId, failureCode,
  resultEvidenceId, revision, createdAt, startedAt, completedAt}`. Response is enveloped
  `{data:[...]}` from the controller; parse `.path("data")`. Cap at a bounded N; **no raw LLM
  prose, no secrets** (the DTO already excludes them). Unknown workflow/step → `[]`.

### B5. `FactoryGetBlockersTool.kt` — `FACTORY__get_blockers`

- `Input(workflowId: String)`; schema per §6.1.
- GET `/api/factory/workflows/{workflowId}/actions?namespaceId={ns}`.
- Output (contract §6.1): JSON array of `WorkflowBlockerDto`-derived
  `{code, stepId, message}` taken from the response `blockers`. `code` is one of the stable
  `WorkflowBlockerCodes` (`WAITING_HUMAN_INTERACTION`, `STEP_BLOCKED`, `ATTEMPT_FAILED`,
  `REAL_COST_PAUSED`, `VERIFICATION_FAILED`, `UNKNOWN_RUNTIME`). Bounded. Covers human gates,
  failed oracles/verification, blocked steps/environment, indeterminate runtime.

### B6. `FactoryGetRequiredHumanActionsTool.kt` — `FACTORY__get_required_human_actions`

- `Input(workflowId: String)`; schema per §6.1.
- GET `/api/factory/workflows/{workflowId}/actions?namespaceId={ns}`.
- Output (contract §6.1): JSON array derived from `allowedActions` of type `reply` only —
  `{interactionId, stepId, questionEventId, prompt(=label), actions:[{id,label}],
  expectedRevision}`. The Factory already restricts `reply` actions to callers it deems
  authorized (`canReply` is set from `principalType==human` + safe actor in the controller),
  so "only interactions the current actor/context is authorized to respond to" is enforced by
  the Factory: when the caller is not an authorized human, `allowedActions` carries no
  `reply` entries and the tool returns `[]`. Synthesize the two-option `actions` list
  (`approve`/`reject`) from the action metadata; do not invent prompts — use the Factory
  `label`/`prompt`. Bounded.

> B5/B6 are **pure reads** of the authoritative `actions` endpoint; they never recompute
> state and never mutate.

---

## Part C — wiring, grant, skill

### C1. `buildFactoryTools` in `FactoryToolPlugin.kt`

File:
`agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryToolPlugin.kt`

- Add the five new tools (B1, B2, B4, B5, B6) to the `listOf(...)` in `buildFactoryTools`
  (B3 extends the existing `FactoryGetWorkflowTool`, so no new entry). Each takes
  `(baseUrl, httpClient, objectMapper)`.
- Update the import block.
- Update `FactoryGetWorkflowToolSpec`'s "tool plugin exposes the full FACTORY capability set"
  `shouldContainExactly(...)` list to include the five new wire names in a defined order.

### C2. Grant allowlist in `FactoryToolGrantService.kt`

File:
`agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryToolGrantService.kt`

- Extend `grantedSuffixes` `when` with the five new suffix/wire-name pairs:
  `get_workstream`, `list_workflows`, `get_step_attempts`, `get_blockers`,
  `get_required_human_actions` (`get_workflow` already present).
- These are read-only and may be granted freely to the Workstream Agent. Do NOT add any
  worker/command suffix.

### C3. Grant policy — leave read tools untouched

File:
`agentos/.../factorybridge/FactoryToolGrantPolicy.kt`

- Do NOT add the read tools to `CAPABILITY_BOUND_TOOLS`. They must return
  `ToolGrantDecision.Neutral` (pass-through) — reads require no capability binding. Add a one
  line KDoc note confirming the six read tools are intentionally not capability-gated.

### C4. Generic Workstream Agent skill

The repo has no `agents/*.yaml` for a Workstream Agent and no skills folder. Create a
**generic, domain-neutral** skill document the Workstream Agent persona loads. Two options —
pick the one that matches how agents consume guidance in this repo:

- **Preferred (doc + agent def):** add a Markdown guidelines file under
  `agentos/agentos-factory-bridge-plugin/docs/workstream-agent-skill.md` (co-located with the
  plugin that owns the tools) describing the read-only operating contract, and reference it
  from the plan. If a Coday agent definition is wanted, add `agents/Workstreamay.yaml`
  granting ONLY the six read tools via the `FACTORY` integration allowlist and pointing its
  `instructions`/`docs` at the skill file.
- The skill text MUST be generic (NO BMAD / Jira / vendor vocabulary) and encode:
  1. **Read before asserting** — always call the relevant read tool before stating workflow
     state.
  2. **Cite the revision** — reference `revision`/`workstreamRevision`/`expectedRevision`
     when describing state so claims are pinned to a version.
  3. **Distinguish fact / interpretation / proposal** — label each statement.
  4. **Never invent state** — if a tool returns no data, say so; do not fabricate steps,
     attempts, blockers or decisions.
  5. **No mutation** — the agent proposes; it never applies transitions, replies to
     checkpoints, cancels attempts or publishes projections. (Proposal/command tools are
     out of this phase's scope.)

> Keep this phase's scope to the read tools + skill; do NOT implement `propose_plan_change`,
> `request_agent_retry`, or `request_human_decision` here (those are the §6.2 command tools,
> a later phase).

---

## Part D — bridge-plugin tests

Under
`agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/`.
Mirror `FactoryGetWorkflowToolSpec` / `FactoryRequestTransitionToolSpec`: Kotest
`StringSpec`, in-process `com.sun.net.httpserver.HttpServer` stub capturing the requested URI
+ returning canned JSON, `ToolContext(namespaceId, userId, externalId, caseEvents, agent)`.

Create one spec per tool:
- `FactoryGetWorkstreamToolSpec.kt`
- `FactoryListWorkflowsToolSpec.kt`
- `FactoryGetStepAttemptsToolSpec.kt`
- `FactoryGetBlockersToolSpec.kt`
- `FactoryGetRequiredHumanActionsToolSpec.kt`

and extend `FactoryGetWorkflowToolSpec.kt` for B3 (merged `allowedActions`/`attempts`/
`evidence` + the updated `shouldContainExactly` tool list).

Each spec asserts:
1. **Schema shape** — `inputSchema` exposes exactly the contract properties,
   `additionalProperties:false`, correct `required`.
2. **Trust injection** — the request URL carries `namespaceId` from `ToolContext` (not from
   input); no identity is read from model input. For `get_workstream`, the model
   `workstreamId` lands in the path only.
3. **Happy path** — a canned Factory response maps to the exact bounded output DTO; assert
   key presence and bounds (`truncated`, `nextCursor`, capped list sizes).
4. **Bounds enforcement** — `list_workflows` coerces `limit` into `[1,200]`, honours
   `cursor`, computes `nextCursor`; oversized/invalid `cursor` → `INVALID_REQUEST`.
5. **Validation / rejection** — blank/overlong/out-of-pattern ids → the right stable code
   (`INVALID_WORKFLOW_ID`, `INVALID_WORKSTREAM_SLUG`, `INVALID_REQUEST`).
6. **Boundary rejection** — a Factory 403 `WORKSTREAM_BOUNDARY_VIOLATION` (and workflow 404 /
   `TRUST_CONTEXT_UNAVAILABLE`) is surfaced as a `failure` with the exact Factory code.
7. **Transport errors** — timeout → `FACTORY_TIMEOUT`; connection failure →
   `FACTORY_UNAVAILABLE`; non-JSON body → `MALFORMED_FACTORY_RESPONSE`; Factory error
   envelope → `FACTORY_REQUEST_FAILED` with the propagated code.
8. **No secrets / no prose** — assert the output contains none of `ownerToken`,
   `capabilityToken`, `commandId`, `brief`, `leaseExpiresAt`, `lastObservedEventId`,
   `turnCorrelation`, and no raw evidence body text.
9. **Grant filtering** — extend the grant test: `FACTORY` allowlist with the five new
   suffixes yields exactly those tool names; an empty allowlist yields none; a worker suffix
   never appears alongside the read set unless explicitly listed. Assert the read tools are
   NOT capability-gated by `FactoryToolGrantPolicy` (they stay `Neutral`).

---

## Build & verification

The factory runs affected tests automatically; run locally only to debug.

- Bridge plugin tests:
  `./gradlew :agentos-factory-bridge-plugin:test` (from `agentos/`), or
  `pnpm nx test agentos-factory-bridge-plugin`.
- Factory service tests:
  `pnpm nx test factory-service` (or target the `workflow` test package).
- Quality gates (factory may run): `pnpm nx affected -t lint build --base="$(cat /work/data/baseline)"`.
- Judge every command by its exit status, not by scanning output for the word "error".

## Acceptance mapping

1. Six read tools with exact wire names, bounded input schemas, validation, stable error
   envelopes → **Part B** + **Part D** specs 1–7.
2. Trust context injected from the boundary; out-of-scope identifiers rejected → **B** trust
   injection + **D** specs 2 & 6 (Factory 403 `WORKSTREAM_BOUNDARY_VIOLATION`).
3. Structured, bounded JSON DTOs; no Markdown → **B** outputs + **D** specs 3–4 & 8.
4. `allowedActions` calculated by Factory and returned in workflow/action queries →
   **B3/B5/B6** (consume the authoritative `/actions` endpoint verbatim).
5. No mutation; no worker tool granted to the Workstream Agent → **C2/C3** (read-only
   allowlist, `Neutral` policy) + **D** spec 9.
6. Generic (no BMAD/Jira) Workstream Agent skill: read-before-assert, cite revision,
   fact/interpretation/proposal, never invent → **C4**.
7. Factory read endpoints updated for the two missing filters, read-only, zero mutation
   touched, with tests → **Part A** + **A3**.
8. `pnpm nx test` passing for both modules.

## Out-of-scope reminder

- §6.2 command tools (`propose_plan_change`, `request_agent_retry`, `request_human_decision`)
  and their write-paths — a later phase.
- DB migrations, Flyway, Neo4j schema, release pipeline.
- Any mutation logic in `factory-service`; the legacy `FactoryTools` Node adapter in
  `libs/integration`.
- The Phase 2/5 aggregates themselves (consume read-only).
