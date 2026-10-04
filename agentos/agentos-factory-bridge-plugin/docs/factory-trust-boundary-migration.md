# Factory bridge — trust-boundary split: FACTORY → FACTORY_WORKSTREAM + FACTORY_WORKER

This document is the **complete inventory** of every `FACTORY` / `FACTORY__*` reference in
the repository (produced before any removal) and the **migration path** operators must
apply to persisted configuration. Nothing was deleted blindly: each reference below is
either migrated, explicitly deprecated, or recorded as out of scope.

## Why the split

The legacy bridge exposed a single config-less `FACTORY` ToolPlugin covering two very
different trust boundaries:

- the **Workstream** persona — read-only visibility over workstreams/workflows;
- the **Worker** persona — case-scoped, capability-bound result submission and questions.

The split gives each boundary its own integration type with its own tools, resolved
through the standard AgentOS mechanism (integration catalog + `IntegrationConfig` +
`ToolResolverService.resolveToolsForRun` + allowlist filtering). The single PF4J plugin
JAR is unchanged — both `ToolPlugin` extensions and every shared bridge service live in
the same `agentos-factory-bridge-plugin` JAR.

---

## A. Integration-type `"FACTORY"` definitions / references

| Reference | Location | Disposition |
|---|---|---|
| `FactoryToolPlugin.INTEGRATION_TYPE = "FACTORY"` | `src/main/.../factorybridge/FactoryToolPlugin.kt` | **Replaced** by `FactoryWorkstreamToolPlugin.INTEGRATION_TYPE = "FACTORY_WORKSTREAM"` and `FactoryWorkerToolPlugin.INTEGRATION_TYPE = "FACTORY_WORKER"` (same JAR). |
| `FactoryToolPlugin.configSchema = null` (config-less → skipped by `CompositeIntegrationTypeRegistry.registerFromPlugin`, so `FACTORY` never appeared in the integration catalog) | `FactoryToolPlugin.kt` / `agentos-service/.../integrationConfig/CompositeIntegrationTypeRegistry.kt` | **Removed.** Both new plugins declare a non-null empty-object `configSchema`, so they register descriptors in the normal catalog. |
| Implicit tool-name prefix `FACTORY__` | every tool under `src/main/.../factorybridge/tools/` | **Re-prefixed** for the 10 exposed tools (see B); deprecated command tools keep their legacy names but are no longer exposed (see D). |

## B. Every `FACTORY__<suffix>` tool name (19 tools)

### In scope — exposed by the new plugins (10)

| Legacy name | New name | New plugin |
|---|---|---|
| `FACTORY__get_workstream` | `FACTORY_WORKSTREAM__get_workstream` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__list_workflows` | `FACTORY_WORKSTREAM__list_workflows` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__get_workflow` | `FACTORY_WORKSTREAM__get_workflow` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__get_step_attempts` | `FACTORY_WORKSTREAM__get_step_attempts` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__get_blockers` | `FACTORY_WORKSTREAM__get_blockers` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__get_required_human_actions` | `FACTORY_WORKSTREAM__get_required_human_actions` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__start_workflow` | `FACTORY_WORKSTREAM__start_workflow` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__request_agent_retry` | `FACTORY_WORKSTREAM__request_agent_retry` | `FactoryWorkstreamToolPlugin` |
| `FACTORY__submit_step_result` | `FACTORY_WORKER__submit_step_result` | `FactoryWorkerToolPlugin` |
| `FACTORY__ask_step_question` | `FACTORY_WORKER__ask_step_question` | `FactoryWorkerToolPlugin` |

> The `FACTORY_WORKSTREAM` boundary is no longer strictly read-only: alongside the six
> reads it carries two boundary request commands. Authority remains strictly with the
> Factory — `FACTORY_WORKSTREAM__start_workflow` creates an authoritative governed
> workflow, and `FACTORY_WORKSTREAM__request_agent_retry` only creates a `pending-human`
> retry request under the revision fence (the agent never decides the retry itself).
> Unblocking or skipping steps remains **human-only** in the Factory cockpit. Neither
> command is capability-gated by `FactoryToolGrantPolicy` (they are Workstream tools, not
> attempt-capability worker callbacks).

> The re-prefix is load-bearing: `ToolResolverService.isToolAllowed` matches
> `toolName == allowed || toolName == "${config.name}__${allowed}"`. An agent listing bare
> suffixes (e.g. `get_workflow`) under an integration **named** `FACTORY_WORKSTREAM` only
> resolves if the tool is literally named `FACTORY_WORKSTREAM__get_workflow`. Name the
> `IntegrationConfig` the same as its type (`FACTORY_WORKSTREAM` / `FACTORY_WORKER`) —
> or list full tool names, which match the `toolName == allowed` branch regardless.

### Command / transition tools — deprecated, NOT exposed (9)

`FACTORY__transition_workflow`, `FACTORY__request_transition`,
`FACTORY__request_human_decision`, `FACTORY__interrupt_attempt`,
`FACTORY__propose_plan_change`, `FACTORY__publish_projection`,
`FACTORY__provision_environment`, `FACTORY__record_agent_result`,
`FACTORY__record_artifact`.

These tool classes are kept (annotated `@Deprecated`) but are wired into **neither** new
plugin: the Workstream/Worker trust boundary deliberately exposes only the six reads, the
two Workstream boundary request commands (`start_workflow`, `request_agent_retry`) and the
two worker capabilities. Their legacy `FACTORY__*` names are left unchanged because
they are no longer routed anywhere (renaming would churn tests with no behavioural gain).
If further command capabilities must be re-exposed later, they belong on the Workstream
plugin under the `FACTORY_WORKSTREAM__*` prefix, behind the standard allowlist — exactly
the path `start_workflow` and `request_agent_retry` followed.

## C. `FactoryToolGrantService` usages

- **Production: none.** `grep -rn "FactoryToolGrantService" agentos/agentos-service` → no
  hits (unlike `QueryUserToolGrantService`/`SkillToolGrantService`/
  `ExchangeToolGrantService`, which `AgentServiceImpl` calls explicitly). It was dead code
  kept alive only by tests.
- **Test-only usages (removed with the class):** `FactoryTestFixtures.grantService()` /
  `tools()` (which used `buildFactoryTools`), plus grant assertions in
  `FactoryGetWorkflowToolSpec`, `FactoryPublishProjectionToolSpec`,
  `FactoryRecordEvidenceToolSpec`, `FactoryStartWorkflowToolSpec`,
  `FactoryRequestTransitionToolSpec`, `FactoryAskStepQuestionToolSpec` and
  `tools/FactoryCommandToolsTest`.
- **Disposition: deleted.** Allowlist filtering is the standard resolver's job
  (`ToolResolverService.extractTools` → `isToolAllowed`); a plugin-side parallel grant
  path no longer exists.

## D. `FactoryToolGrantPolicy` usages

- `STEP_RESULT_TOOL = "FACTORY__submit_step_result"` → `"FACTORY_WORKER__submit_step_result"`.
- `ASK_STEP_QUESTION_TOOL = "FACTORY__ask_step_question"` → `"FACTORY_WORKER__ask_step_question"`.
- Tested by `FactoryBridgeExtensionsSpec` (deny-without-binding / neutral-once-bound /
  deny-on-lookup-failure). Behaviour is unchanged — **fail-closed**: enabling the
  `FACTORY_WORKER` plugin in an `AgentConfig` is NOT sufficient to submit a result
  outside an active attempt binding.

## E. Agent configs / fixtures referencing FACTORY

- **No AgentOS in-repo `AgentConfig`/fixture references `FACTORY`.** Live
  `AgentConfig.integrations` and `IntegrationConfig` records are persisted in Neo4j, not
  in this repository — they are migrated by operators per the table below.
- `forge_bmad/coday/agents/ProductEngineer.yaml` (and other `forge_bmad` prompt YAMLs)
  reference a `FACTORY:` integration key consumed by the **Coday TS adapter**, not by the
  AgentOS Kotlin plugin. **Out of scope — left untouched.**

## F. Docs

| Doc | Reference | Disposition |
|---|---|---|
| `agentos-factory-bridge-plugin/README.md` | extension table (`FactoryToolPlugin`, `FACTORY`), `FACTORY__submit_step_result` | Updated to the two plugins and the `FACTORY_WORKER__*` name. |
| `agentos-factory-bridge-plugin/docs/workstream-agent-skill.md` | six `FACTORY__*` read tools | Re-prefixed to `FACTORY_WORKSTREAM__*`. |
| `agentos/docs/plugin-system.md` | plugin table row (`FACTORY`) | Updated to `FACTORY_WORKSTREAM` / `FACTORY_WORKER`. |
| `factory-service/.../AgentStepQuestionService.kt` (~L62), `factory-service/.../StepQuestion.kt` (~L11) | KDoc-only mention of `FACTORY__ask_step_question` | Comment text updated to `FACTORY_WORKER__ask_step_question` (no behaviour — the factory-service matches by HTTP endpoint, not tool name). |

## G. TS / frontend

- `libs/integration/src/lib/factory.tools.ts` (+ its tests): the **Coday** TS/Express
  adapter, `static readonly TYPE = 'FACTORY'`, already `@deprecated`. Unrelated to the
  AgentOS Kotlin plugin. **Out of scope — left untouched.**
- `apps/*` factory references are cockpit UI, unrelated to the bridge plugin.

## H. Build / PF4J descriptor

- `agentos-factory-bridge-plugin/build.gradle.kts` manifest keeps a single
  `Plugin-Id: agentos-factory-bridge-plugin` and
  `Plugin-Class: io.whozoss.agentos.plugins.factorybridge.FactoryBridgePlugin` — **one
  JAR, unchanged**. Two `@Extension`-annotated `ToolPlugin` classes in the same module
  are discovered independently by PF4J (same pattern as `agentos-http-plugin`); kapt
  regenerates the extension index automatically.
- Shared services (HTTP client, Jackson mapper, step-result binding registry, pending
  checkpoints, state store, SSE high-water marks, config, SSE listeners) stay in
  `FactoryBridgeServices` + `FactoryBridgePluginHolder`, resolved by both plugins through
  the same `() -> FactoryBridgeServices` lambda.

---

## Migration path (deprecated transition compatibility)

AgentOS ships **no automatic alias** for the retired `FACTORY` type: persisted
configuration is migrated explicitly by operators. Nothing is deleted blindly — the
mapping is:

| Old (persisted) | New |
|---|---|
| `IntegrationConfig` of type `FACTORY` used for reads | `IntegrationConfig` of type `FACTORY_WORKSTREAM`, **name it `FACTORY_WORKSTREAM`** |
| `IntegrationConfig` of type `FACTORY` used by workers | `IntegrationConfig` of type `FACTORY_WORKER`, **name it `FACTORY_WORKER`** |
| `AgentConfig.integrations["FACTORY"] = [get_workstream, list_workflows, get_workflow, get_step_attempts, get_blockers, get_required_human_actions]` | `integrations["FACTORY_WORKSTREAM"]` with the same suffixes, plus `start_workflow` and `request_agent_retry` for the two boundary request commands |
| `AgentConfig.integrations["FACTORY"] = [submit_step_result, ask_step_question]` | `integrations["FACTORY_WORKER"]` with the same suffixes |
| Tool names `FACTORY__<suffix>` in allowlists / prompts | `FACTORY_WORKSTREAM__<suffix>` / `FACTORY_WORKER__<suffix>` |
| Command/transition suffixes (`transition_workflow`, `request_transition`, `request_human_decision`, `interrupt_attempt`, `propose_plan_change`, `publish_projection`, `provision_environment`, `record_agent_result`, `record_artifact`) | **Deprecated — no longer exposed by any plugin.** Remove from allowlists; see section B for the re-exposure path if ever needed. |

Transition notes:

1. Create the two new `IntegrationConfig` records (empty `{}` parameters — the schemas
   are empty objects) and re-point each `AgentConfig.integrations` entry per the table.
2. Delete the old `FACTORY` `IntegrationConfig` once no `AgentConfig` references it. An
   `AgentConfig` still pointing at `FACTORY` after the upgrade resolves **no tools**
   (fail-closed — the resolver warns "No plugin found for type FACTORY"), it never
   silently widens.
3. Worker submissions stay fail-closed throughout: `FactoryToolGrantPolicy` denies
   `FACTORY_WORKER__submit_step_result` / `FACTORY_WORKER__ask_step_question` unless the
   running case holds an active attempt binding, regardless of the allowlist.
4. The two reactivated Workstream commands change nothing about human authority:
   unblocking/skipping steps remains **human-only** in the Factory cockpit, and
   `FACTORY_WORKSTREAM__request_agent_retry` creates a `pending-human` request under a
   revision fence — it never executes or approves a retry by itself.
