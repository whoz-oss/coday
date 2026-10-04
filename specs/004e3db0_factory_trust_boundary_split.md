# Plan — Factory bridge real trust boundaries (Workstream vs Worker), single JAR, standard AgentOS mechanism

## Goal

Refactor `agentos/agentos-factory-bridge-plugin` and
`agentos/agentos-service/.../tool/ToolResolverService.kt` so the Factory bridge exposes **two
distinct ToolPlugins in one JAR**, each on its real trust boundary, resolved through the
**standard AgentOS integration flow** (catalog + `IntegrationConfig` + `resolveToolsForRun`
+ allowlist filtering), with the worker submission tools protected fail-closed by
`FactoryToolGrantPolicy`.

Target end state:

- `FACTORY_WORKSTREAM` plugin → the six read-only Workstream tools.
- `FACTORY_WORKER` plugin → the two worker tools (`submit_step_result`, `ask_step_question`).
- Both have a **non-null but empty** `configSchema` (so they appear in the normal integration
  catalog like `CASE`).
- Tools strictly re-prefixed: `FACTORY_WORKSTREAM__*` and `FACTORY_WORKER__*`.
- `ToolResolverService` keeps the standard resolution flow (no Factory special-casing — there
  is none today; this is verified and documented, not removed).
- `FactoryToolGrantService` (test-only, dead in production) is deleted.
- `FactoryToolGrantPolicy` gates `FACTORY_WORKER__submit_step_result` and
  `FACTORY_WORKER__ask_step_question` fail-closed.
- All tests pass.

---

## CRITICAL context the builder must internalise before touching anything

Read these first; they change how several steps are executed.

1. **`ToolResolverService` is already standard.** `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/tool/ToolResolverService.kt`
   has **no** `resolveConfigLessTools`, no `ConfigLessToolGrant`, no Factory branch. Its
   `resolveToolsForRun` → `extractTools` → `isToolAllowed` path is the standard flow already.
   STEP 2's "remove custom config-less handling in ToolResolverService" is therefore a
   **verification + documentation** task, not a deletion. Do NOT invent code to delete. The
   only real change on the resolver side is a KDoc note confirming Factory now resolves
   through the ordinary path. (Grep to confirm: `grep -rn "resolveConfigLess\|ConfigLessToolGrant\|FACTORY\|Factory" ToolResolverService.kt` returns nothing Factory-specific.)

2. **The actual "parallel path" is the config-less-ness of the plugin.** `FactoryToolPlugin`
   declares `override val configSchema: JsonNode? = null`. A null schema makes
   `CompositeIntegrationTypeRegistry.registerFromPlugin` **skip** it
   (`agentos/agentos-service/.../integrationConfig/CompositeIntegrationTypeRegistry.kt`:
   `val schema = plugin.configSchema ?: run { ...skip... return }`), so `FACTORY` never
   surfaces in the integration catalog, even though `ToolRegistryService.pluginsByType` still
   indexes it by type. Giving the two new plugins a **non-null empty-object** schema (exactly
   like `CaseToolPlugin.CONFIG_SCHEMA`) is what puts them in the catalog and on the standard
   flow. This is the concrete meaning of "remove the parallel path".

3. **`FactoryToolGrantService` is NOT wired into production.** `grep -rn "FactoryToolGrantService" agentos/agentos-service`
   returns nothing. It is referenced only from the plugin's own tests (via
   `FactoryTestFixtures.grantService()` / `buildFactoryTools`). Unlike `QueryUserToolGrantService`
   (which `AgentServiceImpl` calls explicitly), FACTORY has no grant-service call site. So
   deleting `FactoryToolGrantService` + `buildFactoryTools` only requires updating the plugin's
   tests. (Contrast confirmed: `AgentServiceImpl` calls `queryUserToolGrantService.grantTools`,
   `skillToolGrantService.grantTools`, `exchangeToolGrantService.grantTools` — never a Factory one.)

4. **Trust boundary is strict and narrow per the prompt's STEP 4 lists.** The two plugins
   expose **only 8 tools total**: 6 workstream reads + 2 worker tools. All other current
   tools (`start_workflow`, `transition_workflow`, `request_transition`,
   `request_human_decision`, `request_agent_retry`, `interrupt_attempt`,
   `propose_plan_change`, `publish_projection`, `provision_environment`,
   `record_agent_result`, `record_artifact`) are **command/transition tools** and are **NOT**
   wired into either new plugin. Per STEP 4 they are marked deprecated and left unexposed (see
   STEP 4 for the exact handling and the rationale/alternative). This is a deliberate scope
   reduction aligned with the acceptance criteria, which only require the 8 partitioned tools.

5. **Out of scope — do not touch:**
   - `libs/integration/src/lib/factory.tools.ts` and friends: this is the **Coday TS/Express
     adapter** (`static readonly TYPE = 'FACTORY'`), already `@deprecated`, unrelated to the
     AgentOS Kotlin plugin. Leave it.
   - `forge_bmad/coday/agents/ProductEngineer.yaml` (`FACTORY:` integration key with suffix
     list): this is a **Coday** agent config consuming the Coday TS `FACTORY` integration, not
     an AgentOS plugin binding. Do not rewrite it. Record it in the inventory and the migration
     note only.
   - Flyway/Neo4j migrations and the release pipeline.
   - `X-Factory-*` HTTP headers and `ExternalContextBinding*` host transport in
     `agentos-service` — unchanged (the host transport stays protocol-identical; only the
     tool-name constants in the plugin change).

---

## Full file map (what exists today)

Plugin: `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/`

- `FactoryToolPlugin.kt` — single plugin, `INTEGRATION_TYPE = "FACTORY"`, `configSchema = null`,
  `provideTools` returns `buildFactoryTools(services())` (all ~19 tools). Contains top-level
  `buildFactoryTools(services): List<StandardTool<*>>`.
- `FactoryToolGrantService.kt` — test-only, dead in prod. DELETE in STEP 2.
- `FactoryToolGrantPolicy.kt` — gates `FACTORY__submit_step_result` + `FACTORY__ask_step_question`;
  reads bindings from `services().stepResultBindings`. Re-prefix in STEP 5.
- `tools/` — one file per tool. The 8 in-scope tools and their current names:
  - `FactoryGetWorkstreamTool.kt` → `FACTORY__get_workstream`
  - `FactoryListWorkflowsTool.kt` → `FACTORY__list_workflows`
  - `FactoryGetWorkflowTool.kt` → `FACTORY__get_workflow`
  - `FactoryGetStepAttemptsTool.kt` → `FACTORY__get_step_attempts`
  - `FactoryGetBlockersTool.kt` → `FACTORY__get_blockers`
  - `FactoryGetRequiredHumanActionsTool.kt` → `FACTORY__get_required_human_actions`
  - `FactorySubmitStepResultTool.kt` → `FACTORY__submit_step_result`
  - `FactoryAskStepQuestionTool.kt` → `FACTORY__ask_step_question`
  - Command/transition tools (NOT exposed by new plugins): `FactoryStartWorkflowTool`,
    `FactoryTransitionWorkflowTool`, `FactoryRequestTransitionTool`,
    `FactoryRequestHumanDecisionTool`, `FactoryRequestAgentRetryTool`,
    `FactoryInterruptAttemptTool`, `FactoryProposePlanChangeTool`,
    `FactoryPublishProjectionTool`, `FactoryEnvironmentTool`
    (`FactoryProvisionEnvironmentTool`), `FactoryRecordEvidenceTool`
    (`FactoryRecordAgentResultTool` + `FactoryRecordArtifactTool`).
- Shared services (STEP 6 — keep in the same JAR, unchanged): `FactoryBridgeServices.kt`,
  `FactoryBridgePluginHolder.kt`, `FactoryBridgeConfig.kt`, `FactoryBridgePlugin.kt`
  (PF4J entry point, `Plugin-Class`), `FactoryStepResultBindingRegistry.kt`,
  `FactoryStepResultBindingController.kt`, `FactoryBindingRegistrar.kt`,
  `FactoryAnswerInterceptor.kt`, `FactoryAnswerAwaiter.kt`,
  `FactoryCaseLifecycleObserver.kt`, `FactoryExternalExecutionContextProvider.kt`,
  `FactoryCheckpointClient.kt`, `FactoryCheckpointRef.kt`,
  `FactoryEnvironmentBindingService.kt`, `FactoryProjectionValidation.kt`,
  `persistence/FactoryBridgeStateStore.kt`, `persistence/FactorySseHighWaterMarkStore.kt`.

PF4J packaging: `agentos/agentos-factory-bridge-plugin/build.gradle.kts` manifest already
declares a single `Plugin-Id`/`Plugin-Class`. Two `@Extension`-annotated `ToolPlugin`
classes in the same module are discovered independently by PF4J (same pattern as
`agentos-http-plugin`). No build change required for the split; kapt regenerates the
extension index.

Tests (plugin): `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/`
- `FactoryTestFixtures.kt` — exposes `tools()` (uses `buildFactoryTools`) and `grantService()`
  (uses `FactoryToolGrantService`). Both references die when those are removed.
- Grant-service assertions appear in: `FactoryGetWorkflowToolSpec.kt`,
  `FactoryPublishProjectionToolSpec.kt`, `FactoryRecordEvidenceToolSpec.kt`,
  `FactoryStartWorkflowToolSpec.kt`, `FactoryRequestTransitionToolSpec.kt`,
  `FactoryAskStepQuestionToolSpec.kt`, `tools/FactoryCommandToolsTest.kt`.
- `FACTORY__*` tool-name assertions appear in: `FactoryBridgeExtensionsSpec.kt` (grant policy,
  4 hits), `FactoryGetWorkflowToolSpec.kt` (30 hits, incl. the "tool plugin exposes the full
  FACTORY capability set and is config-less" test and the read-set grant test),
  `FactoryCommandToolsTest.kt` (19), `FactorySubmitStepResultToolSpec.kt`,
  `FactoryAskStepQuestionToolSpec.kt`, and the per-tool specs.

Docs: `agentos/agentos-factory-bridge-plugin/README.md`,
`agentos/agentos-factory-bridge-plugin/docs/workstream-agent-skill.md`,
`agentos/docs/plugin-system.md`.

---

## Commit plan (one focused commit per step, exact order)

### STEP 1 — Inventory (commit: `docs(factory-bridge): inventory FACTORY references and migration path`)

Produce a complete inventory **before any removal**. Write it to a tracked doc:
`agentos/agentos-factory-bridge-plugin/docs/factory-trust-boundary-migration.md`.

Content required:
- **A. Integration-type `"FACTORY"` definitions/references.** `FactoryToolPlugin.INTEGRATION_TYPE`;
  the type is also the implicit prefix of every tool name.
- **B. Every `FACTORY__<suffix>` tool name** (list all 19 from the `tools/` files; mark which
  8 are in-scope and which 11 are command/transition).
- **C. `FactoryToolGrantService` usages** — confirm production = none (grep output), test =
  `FactoryTestFixtures` + the 7 specs listed above.
- **D. `FactoryToolGrantPolicy` usages** — the two capability-bound constants + the extensions spec.
- **E. Agent config / fixtures referencing FACTORY** — none inside AgentOS repo; note
  `forge_bmad/coday/agents/ProductEngineer.yaml` is a **Coday** config (out of scope) and that
  live AgentOS `AgentConfig.integrations` / `IntegrationConfig` records live in Neo4j, not the repo.
- **F. Docs** — README, workstream-agent-skill.md, plugin-system.md.
- **G. TS/frontend** — `libs/integration/.../factory.tools.ts` (`TYPE='FACTORY'`, already
  `@deprecated`, Coday adapter, out of scope); frontend `apps/*` factory references are cockpit
  UI, unrelated.
- **H. Build / PF4J descriptor** — `build.gradle.kts` manifest `Plugin-Id`/`Plugin-Class`
  (single JAR, unchanged); kapt-generated extension index.
- **Migration path (deprecated transition compatibility).** Provide the mapping table operators
  must apply to persisted data — do NOT delete blindly:
  | Old | New |
  |---|---|
  | `IntegrationConfig` type `FACTORY` (read use) | `FACTORY_WORKSTREAM` |
  | `IntegrationConfig` type `FACTORY` (worker use) | `FACTORY_WORKER` |
  | `AgentConfig.integrations["FACTORY"]` suffix `get_workstream` … `get_required_human_actions` | `integrations["FACTORY_WORKSTREAM"]` same suffixes |
  | `AgentConfig.integrations["FACTORY"]` suffix `submit_step_result` / `ask_step_question` | `integrations["FACTORY_WORKER"]` same suffixes |
  | Tool names `FACTORY__<suffix>` | `FACTORY_WORKSTREAM__<suffix>` / `FACTORY_WORKER__<suffix>` |
  | Command/transition suffixes (`start_workflow`, `transition_workflow`, …) | **deprecated — no longer exposed**; see STEP 4 |
  Note that AgentOS has no automatic alias; operators migrate persisted configs. State this
  explicitly as the deprecation/transition note.

Deliverable: the migration doc committed. No code change in this commit.

### STEP 2 — Remove the parallel path (commit: `refactor(factory-bridge): drop config-less grant path, keep standard resolver`)

- Delete `FactoryToolGrantService.kt`.
- Delete the top-level `buildFactoryTools(...)` function (currently in `FactoryToolPlugin.kt`).
  It is replaced in STEP 3 by two per-plugin builder functions.
- In `FactoryTestFixtures.kt`: remove `grantService()` and `tools()` (both depend on the
  deleted symbols). Replace with helpers the rewritten tests actually need, e.g.
  `workstreamTools(baseUrl)` and `workerTools(baseUrl)` that call the new plugins'
  `provideTools(...)`, plus keep `services(...)`.
- `ToolResolverService.kt`: **no behavioural change.** Add a short KDoc note (near
  `resolveToolsForRun`) stating that config-less plugins no longer bypass this path and that
  Factory now resolves through the ordinary catalog+allowlist flow. Confirm by grep there is
  no Factory/config-less special-casing to remove, and record that confirmation in the commit
  body.
- This step will temporarily break the plugin's tests that reference the removed symbols —
  that is expected; the full green state is reached in STEP 7. (If the factory prefers each
  commit to compile, the test edits for the deleted symbols can be folded forward into STEP 2
  as minimal stubs; otherwise STEP 7 completes them. State chosen approach in the commit body.)

### STEP 3 — Two ToolPlugins in the same JAR (commit: `feat(factory-bridge): split FACTORY into FACTORY_WORKSTREAM and FACTORY_WORKER plugins`)

- Create `FactoryWorkstreamToolPlugin.kt`:
  - `@Extension class FactoryWorkstreamToolPlugin @JvmOverloads constructor(private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current }) : ToolPlugin`
  - `override val integrationType = "FACTORY_WORKSTREAM"`
  - `override val configSchema: JsonNode = EMPTY_OBJECT_SCHEMA` (non-null; see below)
  - `provideTools(...)` returns the six workstream read tools (built from `services()`).
- Create `FactoryWorkerToolPlugin.kt`:
  - `@Extension class FactoryWorkerToolPlugin ...`
  - `override val integrationType = "FACTORY_WORKER"`
  - `override val configSchema: JsonNode = EMPTY_OBJECT_SCHEMA`
  - `provideTools(...)` returns the two worker tools (built from `services()`, passing
    `services.stepResultBindings`).
- Delete `FactoryToolPlugin.kt` (replaced by the two above).
- Empty-but-non-null schema: mirror `CaseToolPlugin.CONFIG_SCHEMA`, e.g.
  ```kotlin
  val EMPTY_OBJECT_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
      """{"type":"object","title":"Factory Workstream Integration","description":"...","properties":{},"additionalProperties":false}"""
  )
  ```
  (One per plugin, with its own title/description.) This makes
  `CompositeIntegrationTypeRegistry.registerFromPlugin` emit a descriptor so each type appears
  in `GET` of the integration catalog, exactly like `CASE`.
- Keep the `() -> FactoryBridgeServices` lambda injection pattern so unit tests can inject fakes.

### STEP 4 — Strictly re-prefix the tools and partition them (commit: `refactor(factory-bridge): re-prefix workstream/worker tools and deprecate command tools`)

- Rename tool `name` constants:
  - Workstream (in their tool files): `FACTORY_WORKSTREAM__get_workstream`,
    `FACTORY_WORKSTREAM__list_workflows`, `FACTORY_WORKSTREAM__get_workflow`,
    `FACTORY_WORKSTREAM__get_step_attempts`, `FACTORY_WORKSTREAM__get_blockers`,
    `FACTORY_WORKSTREAM__get_required_human_actions`.
  - Worker: `FACTORY_WORKER__submit_step_result`, `FACTORY_WORKER__ask_step_question`.
- Move each tool strictly into its plugin's `provideTools` (reads → workstream plugin, the two
  worker tools → worker plugin). No tool appears in both.
- **Why the re-prefix is load-bearing (do not skip or half-do it).** `ToolResolverService.isToolAllowed`
  (`agentos-service/.../tool/ToolResolverService.kt:137-146`) accepts a tool only when
  `toolName == allowed || toolName == "${integrationKey}__$allowed"`, where **`integrationKey = config.name`**
  (the `IntegrationConfig` *name*, line 127 `integrationKey = config.name`). So for an agent that lists bare
  capability suffixes (e.g. `get_workflow`) under an integration whose config name is `FACTORY_WORKSTREAM`,
  the tool MUST be named `FACTORY_WORKSTREAM__get_workflow` or the `${integrationKey}__$allowed` branch fails
  and every tool is silently dropped. This is exactly why each in-scope tool is re-prefixed to match its new
  integration type/name. Operators are expected to name the `IntegrationConfig` the same as the type
  (`FACTORY_WORKSTREAM` / `FACTORY_WORKER`); record this naming convention in the migration doc. (Agents MAY
  instead list full tool names, which match the `toolName == allowed` branch regardless of config name — but
  the prefix-matches-config-name convention is the intended path.)
- **Command/transition tools** (`start_workflow`, `transition_workflow`, `request_transition`,
  `request_human_decision`, `request_agent_retry`, `interrupt_attempt`, `propose_plan_change`,
  `publish_projection`, `provision_environment`, `record_agent_result`, `record_artifact`):
  - Are **not** added to either new plugin (no exposed capability).
  - Mark each tool class `@Deprecated("Not exposed under the Workstream/Worker trust boundary; see docs/factory-trust-boundary-migration.md", level = DeprecationLevel.WARNING)` with a KDoc migration note. Keep the files compiling (they still have direct unit tests).
  - Leave their `FACTORY__<suffix>` names unchanged (they are no longer routed anywhere, so the
    prefix is irrelevant; renaming them would churn their tests for no behavioural gain). Record
    this decision in the migration doc.
  - **Rationale / alternative for reviewers:** The prompt's STEP 4 lists only the 6 reads + 2
    worker tools for the two plugins and says command/transition tools must be "deprecated …
    without exposing unwanted capabilities". The acceptance criteria only require the 8
    partitioned tools. If the reviewer actually wants command tools kept available to the
    Workstream persona, they would be added to `FactoryWorkstreamToolPlugin` under
    `FACTORY_WORKSTREAM__*`; this plan does NOT do that, following the explicit lists. Flagged
    for confirmation.
- Update `FactoryReadSupport`/any shared constants if a prefix is referenced there (grep;
  likely none — names are inline per tool).

### STEP 5 — Adapt FactoryToolGrantPolicy (commit: `refactor(factory-bridge): gate FACTORY_WORKER submission tools fail-closed`)

- In `FactoryToolGrantPolicy.kt` update the companion constants:
  - `STEP_RESULT_TOOL = "FACTORY_WORKER__submit_step_result"`
  - `ASK_STEP_QUESTION_TOOL = "FACTORY_WORKER__ask_step_question"`
  - `CAPABILITY_BOUND_TOOLS = setOf(STEP_RESULT_TOOL, ASK_STEP_QUESTION_TOOL)`
- Preserve fail-closed behaviour exactly: tools not in the set → `Neutral`; in the set →
  `Deny` unless `services().stepResultBindings.contextForCase(caseId, namespaceId).isNotEmpty()`;
  bridge-lookup failure → `Deny` with the generic reason. Having the plugin enabled in
  `AgentConfig` is NOT sufficient to submit outside an active attempt binding — this remains
  the single enforcement point.
- Update the KDoc to the new names and to the Workstream/Worker boundary (the six reads are
  under `FACTORY_WORKSTREAM__*` and are never capability-gated).

### STEP 6 — Shared services stay in the same JAR (commit: `chore(factory-bridge): keep shared bridge services single-JAR under both plugins` — or fold into STEP 3 if no code change)

- Confirm/keep all shared collaborators (HTTP client, Jackson mapper, bindings registry, SSE
  high-water-mark store, pending-checkpoint map, config, SSE listeners, state store) in
  `FactoryBridgeServices` + `FactoryBridgePluginHolder`, resolved by BOTH plugins via the same
  `() -> FactoryBridgeServices` lambda. One PF4J JAR, one `Plugin-Class`
  (`FactoryBridgePlugin`), one holder lifecycle.
- No `build.gradle.kts` manifest change (single `Plugin-Id`/`Plugin-Class` already correct).
  If kapt needs re-run to regenerate the extension index for two extensions, that is automatic.
- If this step requires no distinct code edit beyond STEP 3, state that and fold it into
  STEP 3's commit (do not create an empty commit).

### STEP 7 — Tests, docs, configs (commit: `test(factory-bridge): update specs for Workstream/Worker split and new prefixes`)

Update every test, fixture, and doc so the suite is green:

- `FactoryTestFixtures.kt`: finalise `workstreamTools()` / `workerTools()` helpers calling the
  new plugins; remove all `grantService`/`buildFactoryTools` references.
- `FactoryGetWorkflowToolSpec.kt`:
  - Rewrite the "tool plugin exposes the full FACTORY capability set and is config-less" test
    into two tests: `FactoryWorkstreamToolPlugin` exposes exactly the six
    `FACTORY_WORKSTREAM__*` reads with a **non-null** `configSchema`; `FactoryWorkerToolPlugin`
    exposes exactly `FACTORY_WORKER__submit_step_result` + `FACTORY_WORKER__ask_step_question`
    with a non-null `configSchema`.
  - Rewrite the read-set grant test to assert the workstream plugin's `provideTools` returns
    the six reads (no grant service), and that the worker/command tools are absent.
  - Update all 30 `FACTORY__*` string assertions to `FACTORY_WORKSTREAM__*`.
- `FactoryBridgeExtensionsSpec.kt`: update the grant-policy tests to the new worker names
  (`FACTORY_WORKER__submit_step_result`), and the neutral-read example to
  `FACTORY_WORKSTREAM__get_workflow`.
- `FactorySubmitStepResultToolSpec.kt`, `FactoryAskStepQuestionToolSpec.kt`: update tool-name
  assertions to `FACTORY_WORKER__*`; in the ask-step spec remove the `FactoryToolGrantService`
  `isGranted(...)` block (the grant service is gone) — replace with a worker-plugin
  `provideTools` assertion if coverage is desired.
- Grant-service assertion blocks in `FactoryPublishProjectionToolSpec.kt`,
  `FactoryRecordEvidenceToolSpec.kt`, `FactoryStartWorkflowToolSpec.kt`,
  `FactoryRequestTransitionToolSpec.kt`, `tools/FactoryCommandToolsTest.kt`: **remove the
  grant-service test blocks** (they exercise the deleted `FactoryToolGrantService`). Keep the
  direct tool-execution tests for those command tools (classes still exist, names unchanged);
  they continue to assert `FACTORY__*` and still pass. Optionally add a `@Suppress("DEPRECATION")`
  where the deprecated tool classes are instantiated.
- Docs:
  - `README.md`: update the extension table (two plugins `FACTORY_WORKSTREAM` / `FACTORY_WORKER`,
    single JAR), the `FACTORY__submit_step_result` reference → `FACTORY_WORKER__submit_step_result`,
    and link the migration doc.
  - `docs/workstream-agent-skill.md`: re-prefix the six tool names to `FACTORY_WORKSTREAM__*`.
  - `agentos/docs/plugin-system.md`: update any `FACTORY` catalog reference if present.
  - `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/AgentStepQuestionService.kt`
    (around line 62) and `.../agentattempt/domain/StepQuestion.kt` (around line 11): both carry a
    **KDoc-only** reference to the `FACTORY__ask_step_question` worker tool. These are comments, not
    functional coupling (the factory-service matches by HTTP endpoint `POST /api/factory/agent-step-questions`,
    not by tool name), so no behaviour changes — but update the comment text to
    `FACTORY_WORKER__ask_step_question` so the docs do not go stale. Changing comments in `factory-service`
    will mark it affected; that is acceptable (doc-only).
- No in-repo AgentOS AgentConfig fixtures reference FACTORY, so none to migrate there; the
  migration doc (STEP 1) covers runtime data. Do NOT edit the Coday `ProductEngineer.yaml` or
  the TS adapter.

---

## Verification

Primary (plugin module):
```
pnpm nx test agentos-factory-bridge-plugin
```
or `cd agentos && ./gradlew :agentos-factory-bridge-plugin:test`.

Service module (ensure the resolver note / no behaviour change compiles and existing tests pass):
```
pnpm nx test agentos-service
```

Factory-wide (the factory runs this after the build; you need not run it yourself unless debugging):
```
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
```

Lint/build gates the factory may run:
```
pnpm nx affected -t lint  --base="$(cat /work/data/baseline)"
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
```

Manual acceptance checklist:
- [ ] `grep -rn "INTEGRATION_TYPE" FactoryWorkstreamToolPlugin.kt FactoryWorkerToolPlugin.kt`
      shows `FACTORY_WORKSTREAM` / `FACTORY_WORKER`.
- [ ] Both plugins have non-null `configSchema` (so they register descriptors; verify
      `CompositeIntegrationTypeRegistry` no longer skips them).
- [ ] `grep -rn "FACTORY__" agentos/agentos-factory-bridge-plugin/src/main` shows the six reads
      as `FACTORY_WORKSTREAM__*`, the two worker tools as `FACTORY_WORKER__*`, and only the
      deprecated command tools still bearing the bare `FACTORY__` prefix.
- [ ] `grep -rn "FactoryToolGrantService\|buildFactoryTools" agentos` returns nothing.
- [ ] Each in-scope tool's `FACTORY_WORKSTREAM__`/`FACTORY_WORKER__` prefix matches the integration
      type/name so `isToolAllowed`'s `"${integrationKey}__$allowed"` branch resolves bare suffixes.
- [ ] `FactoryToolGrantPolicy` denies `FACTORY_WORKER__submit_step_result` without an active
      binding and is Neutral once bound (test green).
- [ ] `grep -rn "resolveConfigLess\|ConfigLessToolGrant" agentos` returns nothing;
      `ToolResolverService` unchanged in behaviour.
- [ ] Full affected test suite green.

## Risks / notes for the builder

- **Prompt vs reality mismatch (most important):** `resolveConfigLessTools`/`ConfigLessToolGrant`
  do not exist in the current tree. Treat STEP 2's resolver clause as verify-and-document, not
  delete. The genuine parallel path is config-less-ness (null schema) + the dead
  `FactoryToolGrantService`.
- **Command-tool scope reduction** (STEP 4) is a real behavioural narrowing. It follows the
  prompt's explicit lists and acceptance criteria, but surface it in the PR description for
  reviewer confirmation (alternative documented in STEP 4).
- Two `@Extension` `ToolPlugin`s in one module is supported (same as `agentos-http-plugin`);
  ensure kapt re-generates the extension index (clean build if the index looks stale).
- Keep each commit focused and in order; if a mid-sequence commit cannot compile because of the
  symbol removals, either carry minimal forward-compatible test stubs in STEP 2 or state in the
  commit body that green is reached at STEP 7 — pick one and be consistent.
- Do not touch migrations, the release pipeline, the Coday TS adapter, or the AgentOS host
  `X-Factory-*` transport.
