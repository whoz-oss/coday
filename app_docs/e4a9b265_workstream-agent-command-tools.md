# Phase 7 — Workstream Agent command tools (agentos-factory-bridge-plugin)

## What changed and why

The bridge plugin now exposes the six governed **command** tools that let a Workstream Agent act on
the Factory without ever becoming the authority. Three tools are new, three existing ones were
aligned, and all command responses now follow one standardized output contract.

Key principles enforced by this change:

- **Propose, never decide.** Every tool only submits a command; the Factory validates under revision
  fences, budgets and policy, and its verdict is reflected in the tool output (`accepted` /
  `rejected` / `pending-human`).
- **Trusted identity, never model-authored.** No tool schema contains `namespaceId`, `agentId`,
  `caseId`, `actorId`, `runtimeId` or any token/URL field. The execution identity is derived from
  the trusted `ToolContext` and transported via `x-factory-*` headers (plus the `namespaceId` body
  hint the endpoints accept). Each tool fails closed before any HTTP call when the context is
  incomplete: `CASE_CONTEXT_UNAVAILABLE` (not exactly one controlling case),
  `AGENT_CONTEXT_UNAVAILABLE`, `USER_CONTEXT_UNAVAILABLE`.
- **Standardized command output.** On success every command tool returns a JSON object with
  `status`, `revision`, `reasonCode`, `interactionId`, `proposalId`, `allowedActions` (best-effort
  enrichment from `GET /api/factory/workflows/{id}/actions` — a failed read yields `[]` and never
  fails the command) and `message`. Rejections and transport failures stay
  `ToolExecutionResult.error` with the Factory `error.code` carried verbatim, or stable bridge codes
  (`FACTORY_UNAVAILABLE`, `FACTORY_TIMEOUT`, `MALFORMED_FACTORY_RESPONSE`, `FACTORY_REQUEST_FAILED`).

## The tools

| Wire name | Class | Endpoint | Output status |
|---|---|---|---|
| `FACTORY__start_workflow` (existing, standardized) | `FactoryStartWorkflowTool` | `POST …/workflows/{id}/start` | `accepted` |
| `FACTORY__request_transition` (existing, standardized) | `FactoryRequestTransitionTool` | `POST …/workflows/{id}/transitions` | `accepted` (policy decided, `changed` preserved) |
| `FACTORY__request_human_decision` (existing, doc-only touch) | `FactoryRequestHumanDecisionTool` | `POST …/workflows/{id}/interactions` | inherently `pending-human` — success suspends the run, no success JSON is emitted |
| `FACTORY__request_agent_retry` (new) | `FactoryRequestAgentRetryTool` | `POST …/workflows/{id}/retries` | `pending-human` (opens a `retry` interaction, `reasonCode: "retry_requested"`) |
| `FACTORY__interrupt_attempt` (new) | `FactoryInterruptAttemptTool` | `POST …/workflows/{id}/attempts/{attemptId}/cancel` | `accepted` (`reasonCode` = attempt status, e.g. `interrupted`) |
| `FACTORY__propose_plan_change` (new) | `FactoryProposePlanChangeTool` | `POST /api/factory/plan-change-proposals` | `AUTO_APPLIED` → `accepted`; `GATE_REQUIRED` / `PENDING_VALIDATION` / `REQUIRES_NEW_DEFINITION` → `pending-human`; `REJECTED` → `rejected` |

Endpoint allowlists are respected exactly: the retry body is only
`{namespaceId, stepId, expectedRevision, reasonCode}` (the model-authored `idempotencyKey` is **not**
forwarded); the cancel body is only `{namespaceId, expectedRevision, reason?}` (`attemptId` travels
in the path); the plan-change body mirrors `SubmitPlanChangeRequest` with `namespaceId` injected
from the context and `proposalType` enum-bounded
(`RETRY|PATH_SELECTION|OPTIONAL_STEP|DEPENDENCY|SCOPE|NEW_STEP|CONTRACT_OR_ORACLE`).

## Files

Created (3 tools + 1 test):
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryRequestAgentRetryTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryInterruptAttemptTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryProposePlanChangeTool.kt`
- `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryCommandToolsTest.kt` — 466-line Kotest `StringSpec`: schema strictness (no identity fields), exact wire payloads and trust headers via a fake `HttpServer` Factory, standardized outputs, context-guard failures, Factory error-code propagation, and grant-service wiring.

Updated:
- `tools/FactoryStartWorkflowTool.kt` — standardized output + best-effort `allowedActions` enrichment in `execute`; `parseResponse` keeps the rich start fields (`created`, `idempotent`, `governanceMode`, …).
- `tools/FactoryRequestTransitionTool.kt` — standardized output (`status/revision/allowedActions/message`, `changed` preserved) + the same enrichment helper.
- `tools/FactoryRequestHumanDecisionTool.kt` — KDoc only; the suspend-on-success contract is unchanged.
- `FactoryToolPlugin.kt` (`buildFactoryTools`) — instantiates the three new tools.
- `FactoryToolGrantService.kt` — maps the three new suffixes (bare and `FACTORY__`-prefixed); a comment pins the §5 capability matrix: the Workstream Agent persona may be granted `get_*` reads + `request_human_decision` + `propose_plan_change` + `request_agent_retry`, and **never** `start_workflow`, `request_transition`, `interrupt_attempt`, `submit_step_result`, `ask_step_question`, `record_*`, `provision_environment` or `publish_projection`. Grants remain explicit-only (absent/empty allowlist grants nothing).
- `FactoryGetWorkflowToolSpec.kt`, `FactoryStartWorkflowToolSpec.kt`, `FactoryRequestTransitionToolSpec.kt` — updated for the new tool list / standardized outputs; the fake servers now distinguish POST (command) from GET (actions enrichment).
- `app_docs/workstream_agent_cartography_and_contracts.md` — §2.1 tool table completed (three new rows, "implemented Phase 7" marks), new §6.2 note documenting the standardized output contract and grants, §6.2 schemas aligned with the shipped wire shapes (notably `propose_plan_change` using `proposalType` + `proposedDependencyChanges`/`proposedScopeChanges`).
- `specs/e4a9b265_workstream_agent_command_tools.md` — the full Phase 7 spec (recon, contracts, file-by-file plan, boundaries).

No factory-service file was touched — the three endpoints and the PlanChangeProposal store already
existed; this is a bridge-plugin + docs change only. No migrations, no `agentattempt/` changes.

## How to use / verify

Grant a Workstream Agent persona an explicit `FACTORY` allowlist such as:
`get_workflow, get_workstream, list_workflows, get_step_attempts, get_blockers,
get_required_human_actions, request_human_decision, propose_plan_change, request_agent_retry`
— the grant service test (`FactoryCommandToolsTest`) asserts exactly this allowlist and that the
worker/authority tools stay out of it.

Run the module tests:

```
cd agentos/agentos-factory-bridge-plugin && ../gradlew test
# or: pnpm nx test agentos-factory-bridge-plugin
```
