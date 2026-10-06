# Phase 6 — Read-only Workstream Agent tools

## What changed

Six read-only Factory tools were implemented in `agentos/agentos-factory-bridge-plugin/`
and wired into `buildFactoryTools`, giving the Workstream Agent persona a useful
observation surface with **zero mutation capability**. They sit on top of Phase 5
(workstream aggregate projection) and Phase 2 (`DurableAgentAttemptDto`) endpoints, and
are complemented by two additive, read-only query-parameter filters in
`factory-service/`.

### The six tools (`.../plugins/factorybridge/tools/`)

| Tool | Input | Endpoint hit |
|---|---|---|
| `FACTORY__get_workstream` | `workstreamId` | `GET /api/factory/workstreams/{id}/projection` |
| `FACTORY__list_workflows` | `state`, `workflowType`, `limit`, `cursor` (all optional) | `GET /api/factory/workflows` |
| `FACTORY__get_workflow` | `workflowId` | `GET /api/factory/workflows/{id}` + `/actions`, `/attempts`, `/evidence` sub-reads |
| `FACTORY__get_step_attempts` | `workflowId`, `stepId` | `GET /api/factory/workflows/{id}/attempts?stepId=…` |
| `FACTORY__get_blockers` | `workflowId` | `GET /api/factory/workflows/{id}/actions` (blockers section) |
| `FACTORY__get_required_human_actions` | `workflowId` | `GET /api/factory/workflows/{id}/actions` (reply actions only) |

Shared behavior lives in the new **`FactoryReadSupport.kt`** helper: identifier regexes
(`WORKFLOW_ID`, `SAFE_ID`), URL encoding, GET execution on `Dispatchers.IO`, and the
stable error envelope — `FACTORY_TIMEOUT`, `FACTORY_UNAVAILABLE`,
`MALFORMED_FACTORY_RESPONSE`, `FACTORY_REQUEST_FAILED`, plus Factory `error.code` /
`error.message` carried through verbatim (e.g. a 403 `WORKSTREAM_BOUNDARY_VIOLATION`).

Key design points, traceable to the code:

- **Trust context from the boundary.** `namespaceId` is always injected from the trusted
  `ToolContext`, never from model input. Identifiers are validated against bounded
  regexes before any network call (`INVALID_WORKFLOW_ID`, `INVALID_WORKSTREAM_SLUG`,
  `INVALID_REQUEST`). Out-of-scope identifiers are rejected server-side by the Factory.
- **Bounded, structured JSON.** Outputs are DTO-shaped maps with per-section caps
  (`MAX_BLOCKERS`, `MAX_ACTIONS`, `MAX_ATTEMPTS`, `MAX_SUB_ITEMS` = 50; list
  `DEFAULT_LIMIT` 50 / `MAX_LIMIT` 200) and `count` / `truncated` metadata. No
  preformatted Markdown.
- **`allowedActions` is Factory-calculated.** `get_workflow` merges the `/actions`
  response verbatim; `get_required_human_actions` only projects the `reply` actions the
  Factory already restricted to authorized callers (an unauthorized caller gets `[]`).
- **Secret-free attempts.** `FactoryGetStepAttemptsTool.boundedAttempt` (reused by
  `get_workflow`'s enrichment) projects only the public `DurableAgentAttemptDto` fields —
  no execution secrets, no `resumptionContext`, no raw LLM prose; evidence is summarized
  to `evidenceId`/`stepId`/`kind`/`createdAt`.
- **Degradation, not failure.** In `get_workflow`, a failing sub-read (actions / attempts
  / evidence) degrades to an empty section while the primary projection stays
  authoritative. Unknown workflow/step lists degrade to `[]` with HTTP 200.
- **Pagination is mandatory in `list_workflows`.** `limit` is coerced into `[1, 200]`;
  `cursor` is an opaque integer-offset token; the tool requests `offset + limit` (capped)
  server-side and drops the offset client-side, emitting `nextCursor` only while items
  remain. `purged` / `all` map to the supported bounded `active` view.

### Wiring and grants

- **`FactoryToolPlugin.kt`** — the five new tools plus the enriched
  `FactoryGetWorkflowTool` are registered first in `buildFactoryTools`.
- **`FactoryToolGrantService.kt`** — the six read tool names (both bare and
  `FACTORY__`-prefixed forms) are now grantable via the FACTORY integration allowlist.
- **`FactoryToolGrantPolicy.kt`** — doc-only change: the read tools are intentionally
  **not** capability-gated (they stay `ToolGrantDecision.Neutral`); no case-scoped
  capability is required for pure reads.
- No worker/command tool (`submit_step_result`, `ask_step_question`,
  `record_agent_result`, transitions, retries, etc.) is granted to the Workstream Agent —
  a test asserts the read grant set exposes exactly the six reads and nothing else.

### Workstream Agent skill

**`agentos/agentos-factory-bridge-plugin/docs/workstream-agent-skill.md`** defines the
generic, domain-neutral operating contract: read before asserting, cite the revision on
every state claim, label statements as fact / interpretation / proposal, never invent
state, never mutate, and respect the trusted boundary (report rejections, never retry
with altered identifiers).

### Factory service (read-only additions)

- **`WorkflowService.listProjections`** gained optional `workflowType` and `limit`
  filters (both `null`-neutral). The type filter matches projected
  `projection.workflowType`; `limit` is coerced into `[1, MAX_LIST_LIMIT]` (200) and the
  response now reports `truncated`.
- **`WorkflowController`** — `GET /api/factory/workflows` accepts `workflowType` and
  `limit` query params; `GET /api/factory/workflows/{id}/attempts` accepts an optional
  `stepId` filter (pure in-memory filter over the DTO list; unknown step → `{"data":[]}`
  with 200).

No mutation logic was touched; no DB migrations or release pipeline files were modified.

## Files

Plugin (new): `tools/FactoryGetWorkstreamTool.kt`, `tools/FactoryListWorkflowsTool.kt`,
`tools/FactoryGetStepAttemptsTool.kt`, `tools/FactoryGetBlockersTool.kt`,
`tools/FactoryGetRequiredHumanActionsTool.kt`, `tools/FactoryReadSupport.kt`,
`docs/workstream-agent-skill.md`.

Plugin (modified): `tools/FactoryGetWorkflowTool.kt` (enrichment),
`FactoryToolPlugin.kt`, `FactoryToolGrantService.kt`, `FactoryToolGrantPolicy.kt`.

Factory service: `workflow/service/WorkflowService.kt`, `workflow/web/WorkflowController.kt`.

Tests: six new plugin specs (`FactoryGet*ToolSpec.kt`, `FactoryListWorkflowsToolSpec.kt`)
covering schemas, bounds, trust-context injection, error envelopes and transport codes;
`FactoryGetWorkflowToolSpec.kt` extended for enrichment/degradation/grant-set;
`WorkflowControllerHttpTest.kt` extended for the `stepId`, `workflowType` and `limit`
filters. The full plan is recorded in `specs/4216a9a0_workstream_agent_read_tools.md`.

## How to verify

- Plugin tests: `pnpm nx test agentos-factory-bridge-plugin` (Kotest specs spin up a
  local `HttpServer` and assert exact request paths, including injected `namespaceId`).
- Service tests: `pnpm nx test factory-service` (`WorkflowControllerHttpTest` covers the
  new filters end to end).
- Manual smoke: call `FACTORY__list_workflows` with `limit = 500` — the server request
  shows `limit=200`; request `state = "purged"` — the server request shows
  `state=active`; a bad `workflowId` like `"bad id"` fails with
  `INVALID_WORKFLOW_ID` before any network call.
