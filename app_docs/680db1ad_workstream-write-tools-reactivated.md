# Reactivating two write tools on the `FACTORY_WORKSTREAM` trust boundary

## What changed

The `FACTORY_WORKSTREAM` integration in `agentos/agentos-factory-bridge-plugin` grew from
6 read-only tools to **8 tools**. Two previously dormant (deprecated, unexposed) command
tools were re-prefixed from `FACTORY__*` to `FACTORY_WORKSTREAM__*` and wired into
`FactoryWorkstreamToolPlugin`:

- `FACTORY_WORKSTREAM__start_workflow` (`FactoryStartWorkflowTool`) — creates an
  authoritative governed workflow from the unique configured immutable definition.
- `FACTORY_WORKSTREAM__request_agent_retry` (`FactoryRequestAgentRetryTool`) — requests a
  governed retry of a blocked step. It only opens a **`pending-human`** request under a
  **revision fence**; the agent never decides or executes the retry. Unblocking/skipping
  steps stays human-only in the Factory cockpit.

For each tool class the *only* code change was removing `@Deprecated` and changing
`override val name`. HTTP logic, trust context, `x-factory-*` headers, `expectedRevision`,
payloads, and constructor parameters are untouched.

`FactoryWorkstreamToolPlugin.buildFactoryWorkstreamTools` now appends both tools to the
six reads, passing `services.config.runtimeId` as the fourth constructor argument
(matching their existing constructors). The plugin KDoc and the `CONFIG_SCHEMA`
description were rewritten: the integration is "no longer strictly read-only" but
authority remains with the Factory. The schema stays a non-null empty object
(`"properties": {}`), so the plugin still registers in the standard integration catalog.

`FactoryToolGrantPolicy` had **no logic change** (KDoc only): the two reactivated tools
fall through to `ToolGrantDecision.Neutral` — they are Workstream boundary commands, not
attempt-capability worker callbacks, so they must not join `CAPABILITY_BOUND_TOOLS`. The
`FACTORY_WORKER` surface (`submit_step_result`, `ask_step_question`) remains fail-closed
and unchanged.

Everything else under `FACTORY__*` (`transition_workflow`, `request_transition`,
`interrupt_attempt`, `request_human_decision`, `propose_plan_change`,
`publish_projection`, `record_*`, `provision_environment`) stays `@Deprecated` and is
exposed by neither plugin.

## Why it matters

The Workstream Agent can now *propose and request* — start a governed workflow and ask
for a retry — without ever gaining apply-authority. Both commands keep the Factory as
the sole decision point: retry requests land as `pending-human` under the revision
fence, and no case-scoped capability is minted. The re-prefix matters operationally
because `ToolResolverService.isToolAllowed` matches names against the integration
prefix, so allowlists must use `start_workflow` / `request_agent_retry` suffixes under
`FACTORY_WORKSTREAM` (or the full tool names).

## Files that carry it

Main (`agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/`):
- `tools/FactoryStartWorkflowTool.kt` — `@Deprecated` removed, renamed.
- `tools/FactoryRequestAgentRetryTool.kt` — `@Deprecated` removed, renamed.
- `FactoryWorkstreamToolPlugin.kt` — wires the two tools, updated KDoc + schema description.
- `FactoryToolGrantPolicy.kt` — KDoc only; confirms the two commands are not capability-gated.

Tests (`.../src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/`):
- `FactoryGetWorkflowToolSpec.kt` — surface tests now assert exactly 8
  `FACTORY_WORKSTREAM__*` tools; both new tools evaluate `Neutral` in the grant policy; a
  new test asserts strict Workstream/Worker separation and that the nine deprecated
  command/transition tool names appear on neither surface.
- `FactoryStartWorkflowToolSpec.kt` — asserts the new tool name.
- `tools/FactoryCommandToolsTest.kt` — asserts `request_agent_retry` carries the new name
  while `interrupt_attempt` / `propose_plan_change` keep legacy `FACTORY__*` names.

Docs:
- `agentos/agentos-factory-bridge-plugin/docs/factory-trust-boundary-migration.md` —
  exposed table now counts 10 tools (8 Workstream + 2 Worker); deprecated list shrinks to
  9; migration table maps the two suffixes onto `FACTORY_WORKSTREAM`; explicit note that
  retry stays `pending-human` and unblocking/skipping is human-only.
- `agentos/agentos-factory-bridge-plugin/README.md` — plugin table row updated to 8 tools
  with the authority caveat.
- `agentos/agentos-factory-bridge-plugin/docs/workstream-agent-skill.md` — Workstream
  Agent skill now lists 8 tools and frames the two commands as request-only.

Spec (input artefact): `specs/680db1ad_reactivate_workstream_write_tools.md`.

## How to use / verify

Grant the two commands to an agent via its integration allowlist:

```yaml
integrations:
  FACTORY_WORKSTREAM:
    - get_workstream
    - list_workflows
    - get_workflow
    - get_step_attempts
    - get_blockers
    - get_required_human_actions
    - start_workflow
    - request_agent_retry
```

Verify from the `agentos/` composite build root:

```bash
cd agentos && ./gradlew :agentos-factory-bridge-plugin:test
```

Expected: `FactoryGetWorkflowToolSpec` passes with 8 Workstream tools, the two commands
return `ToolGrantDecision.Neutral`, worker callbacks still deny without an active attempt
binding, and no legacy `FACTORY__*` command name appears on either plugin surface.
