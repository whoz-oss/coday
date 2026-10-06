# Plan — Reactivate two write capabilities on `FACTORY_WORKSTREAM`

## Goal

Reactivate exactly TWO sleeping write tools at the Workstream trust boundary of the
`agentos/agentos-factory-bridge-plugin` module:

- `FactoryStartWorkflowTool` → renamed `FACTORY_WORKSTREAM__start_workflow`
- `FactoryRequestAgentRetryTool` → renamed `FACTORY_WORKSTREAM__request_agent_retry`

The `FACTORY_WORKSTREAM` integration surface grows from **6 read tools → 8 tools**
(6 reads + the 2 reactivated commands). The `FACTORY_WORKER` surface (2 fail-closed
callbacks) is **untouched**. All other deprecated command/transition tools stay
`@Deprecated` and remain unexposed.

Base branch: `sbx/coday-factory-workstream-commands-8602` (HEAD `230c14da2`).

**No behavioural change** to the two tools' HTTP logic, trust context, `x-factory-*`
headers, `expectedRevision`, payloads, or constructor parameters — the ONLY code change
to the tool classes is removing `@Deprecated` and changing `override val name`.

---

## Context: current state (verified)

- `FactoryWorkstreamToolPlugin` (type `FACTORY_WORKSTREAM`) currently returns 6 reads via
  `buildFactoryWorkstreamTools(services)`.
- `FactoryWorkerToolPlugin` (type `FACTORY_WORKER`) returns 2 capability-bound callbacks.
- `FactoryStartWorkflowTool` constructor: `(baseUrl, httpClient, objectMapper, runtimeId: String)`.
  It is currently `@Deprecated(level = WARNING)`, name = `"FACTORY__start_workflow"`.
- `FactoryRequestAgentRetryTool` constructor: `(baseUrl, httpClient, objectMapper, runtimeId: String)`.
  Currently `@Deprecated(level = WARNING)`, name = `"FACTORY__request_agent_retry"`.
  Already emits `status = "pending-human"` with revision fence + `x-factory-*` headers.
- `services.config.runtimeId` exists (`FactoryBridgeConfig.runtimeId: String`), already
  passed to worker tools' sibling plugin via `services.config` — safe to use here.
- `FactoryToolGrantPolicy` only capability-gates `FACTORY_WORKER__submit_step_result` and
  `FACTORY_WORKER__ask_step_question` (via `CAPABILITY_BOUND_TOOLS` set). Everything else
  is `Neutral`. **No change needed** — the two reactivated tools fall through to Neutral,
  which is correct (retry authority stays with the Factory via the pending-human revision
  fence, not via an attempt capability).

---

## Step 1 — Re-prefix and un-deprecate the two tools

### File `src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryStartWorkflowTool.kt`
- Remove the entire `@Deprecated(...)` annotation block (lines ~16–19, directly above
  `class FactoryStartWorkflowTool`).
- Change `override val name = "FACTORY__start_workflow"` → `override val name = "FACTORY_WORKSTREAM__start_workflow"`.
- Leave everything else untouched (Input, inputSchema, execute, parseResponse,
  fetchAllowedActions, failure, constructor params).

### File `src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryRequestAgentRetryTool.kt`
- Keep the descriptive KDoc block (the one starting "Phase 7 command tool: request a
  governed retry…") — it documents the pending-human/revision-fence behaviour.
- Remove ONLY the `@Deprecated(...)` annotation block (lines ~26–29, directly above
  `class FactoryRequestAgentRetryTool`).
- Change `override val name = "FACTORY__request_agent_retry"` → `override val name = "FACTORY_WORKSTREAM__request_agent_retry"`.
- Leave everything else untouched (Input, inputSchema, execute, headers, body allowlist,
  fetchAllowedActions, encode, fail, constructor params).

---

## Step 2 — Wire the two tools into `FactoryWorkstreamToolPlugin`

### File `src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryWorkstreamToolPlugin.kt`

Add imports:
```kotlin
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryStartWorkflowTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestAgentRetryTool
```

In `buildFactoryWorkstreamTools`, append the two tools to the returned list (after the 6
reads), passing `services.config.runtimeId`:
```kotlin
return listOf(
    FactoryGetWorkflowTool(baseUrl, httpClient, objectMapper),
    FactoryGetWorkstreamTool(baseUrl, httpClient, objectMapper),
    FactoryListWorkflowsTool(baseUrl, httpClient, objectMapper),
    FactoryGetStepAttemptsTool(baseUrl, httpClient, objectMapper),
    FactoryGetBlockersTool(baseUrl, httpClient, objectMapper),
    FactoryGetRequiredHumanActionsTool(baseUrl, httpClient, objectMapper),
    FactoryStartWorkflowTool(baseUrl, httpClient, objectMapper, services.config.runtimeId),
    FactoryRequestAgentRetryTool(baseUrl, httpClient, objectMapper, services.config.runtimeId),
)
```
(`baseUrl`, `httpClient`, `objectMapper` locals are already bound at the top of the fn;
`services` is the parameter.)

---

## Step 3 — Update KDoc + `CONFIG_SCHEMA` description of `FactoryWorkstreamToolPlugin`

Same file. Update the function KDoc on `buildFactoryWorkstreamTools` ("Builds the six
read-only…") and the class-level KDoc so they describe **8 tools**: the six reads plus two
boundary request commands (`FACTORY_WORKSTREAM__start_workflow` and governed
`FACTORY_WORKSTREAM__request_agent_retry`). Note that the integration is no longer strictly
read-only, but authority remains with the Factory: `start_workflow` creates a governed
workflow and `request_agent_retry` opens a `pending-human` request under the revision fence.

Update `CONFIG_SCHEMA`'s `"description"` string accordingly, e.g.:
> "Workstream trust boundary for the Workstream Agent persona: six read-only views over
> Factory workstreams/workflows plus two governed boundary request commands
> (start_workflow, request_agent_retry). Authority stays with the Factory —
> request_agent_retry opens a pending-human request under a revision fence. No
> configuration required."

Keep the schema shape unchanged: `"type": "object"`, non-null, `"properties": {}`,
`"additionalProperties": false`. Do NOT make `configSchema` nullable.

---

## Step 4 — Verify `FactoryToolGrantPolicy` (NO code change expected)

### File `src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryToolGrantPolicy.kt`
- Confirm `CAPABILITY_BOUND_TOOLS` contains ONLY `FACTORY_WORKER__submit_step_result` and
  `FACTORY_WORKER__ask_step_question`. The two reactivated tools must NOT be added here —
  they are Workstream request commands, not attempt-capability callbacks. They correctly
  fall through to `ToolGrantDecision.Neutral`.
- Optionally extend the policy's KDoc (the paragraph listing the non-gated Workstream
  reads) to mention that `FACTORY_WORKSTREAM__start_workflow` and
  `FACTORY_WORKSTREAM__request_agent_retry` are likewise not capability-gated — governed
  authority for retry stays with the Factory (pending-human under revision fence). KDoc
  only; no logic change.
- `FACTORY_WORKER` callbacks remain fail-closed and unchanged.

---

## Step 5 — Tests

### 5a. `src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryStartWorkflowToolSpec.kt`
- The spec constructs `FactoryStartWorkflowTool(...)` directly — no `@Deprecated` call-site
  change needed, but the tool's NAME is now `FACTORY_WORKSTREAM__start_workflow`. The
  existing assertions check schema/payload/output, not `tool.name`. Add an explicit
  assertion of the new name for clarity:
  `tool.name shouldBe "FACTORY_WORKSTREAM__start_workflow"` (inside the first test, after
  constructing `tool`). No other change required.

### 5b. `src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/tools/FactoryCommandToolsTest.kt`
- Update the file-level KDoc comment that references `FACTORY__request_agent_retry` to the
  new name.
- In the `request_agent_retry` tests, the tool is constructed directly and assertions key
  off output JSON / captured HTTP, not `tool.name`. Add
  `tool.name shouldBe "FACTORY_WORKSTREAM__request_agent_retry"` in the first
  `request_agent_retry` test for clarity.
- `interrupt_attempt` and `propose_plan_change` tests reference `FactoryInterruptAttemptTool`
  and `FactoryProposePlanChangeTool`, which stay `@Deprecated`. Direct construction of a
  `@Deprecated(level = WARNING)` class only emits a warning, not an error — leave those
  tests as-is (do NOT reactivate those tools).

### 5c. Plugin surface tests (grow 6 → 8) in
`src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryGetWorkflowToolSpec.kt`

This file holds the authoritative surface assertions. Two tests must change:

1. Test **"workstream plugin exposes exactly the six reads and they stay Neutral in the
   grant policy"**:
   - Rename intent to 8 tools. Update the expected set so
     `FactoryTestFixtures.workstreamTools().map { it.name }.toSet()` equals the 6 reads
     **plus** `FACTORY_WORKSTREAM__start_workflow` and `FACTORY_WORKSTREAM__request_agent_retry`.
   - Extend the `policy.evaluateToolGrant(...)` Neutral loop to also assert
     `FACTORY_WORKSTREAM__start_workflow` and `FACTORY_WORKSTREAM__request_agent_retry`
     return `ToolGrantDecision.Neutral` (not capability-gated).
   - Keep the worker-tools assertion unchanged
     (`FACTORY_WORKER__submit_step_result`, `FACTORY_WORKER__ask_step_question`).

2. Test **"workstream tool plugin exposes exactly the six Workstream reads and registers
   in the catalog"**:
   - Add the two new names to the `.shouldContainExactly(...)` list (order:
     after the 6 reads, matching the `buildFactoryWorkstreamTools` list order —
     `shouldContainExactly` is order-sensitive, so match the exact list order:
     get_workflow, get_workstream, list_workflows, get_step_attempts, get_blockers,
     get_required_human_actions, start_workflow, request_agent_retry).
     NOTE: the current test lists reads in a different order than the plugin returns them.
     Verify the actual returned order and align `shouldContainExactly` to it, or switch to
     `shouldContainExactlyInAnyOrder` if order drift is a concern. Prefer matching the
     plugin's real list order.
   - Update the test display name / comment to say "eight" instead of "six".

### 5d. Strict separation assertions
- The "worker tool plugin exposes exactly the two worker tools" test already asserts the
  worker surface; keep it. It implicitly proves `FACTORY_WORKSTREAM__*` tools are not on
  the worker plugin.
- Add an explicit cross-check (either in the updated surface test or a new small test):
  assert that `FactoryTestFixtures.workstreamTools().map { it.name }` contains NO
  `FACTORY_WORKER__*` names, and `FactoryTestFixtures.workerTools().map { it.name }`
  contains NO `FACTORY_WORKSTREAM__*` names.

### 5e. Deprecated-and-unexposed assertion
- Add assertions that out-of-scope command/transition tools are neither on the Workstream
  nor Worker surface. Compute the union of
  `FactoryTestFixtures.workstreamTools() + workerTools()` names and assert it does NOT
  contain any of: `FACTORY__transition_workflow`, `FACTORY__request_transition`,
  `FACTORY__interrupt_attempt`, `FACTORY__request_human_decision`,
  `FACTORY__propose_plan_change`, `FACTORY__publish_projection`,
  `FACTORY__provision_environment`, `FACTORY__record_agent_result`,
  `FACTORY__record_artifact`. (These tools keep their legacy `FACTORY__*` names and
  `@Deprecated` annotations.)
- The requirement mentions asserting those classes remain `@Deprecated`. There is no clean
  reflective check for a Kotlin `@Deprecated(level=WARNING)` without extra ceremony; the
  load-bearing guarantee is that they are NOT EXPOSED. Satisfy the requirement with the
  not-exposed assertion above (primary), and optionally a comment noting the classes retain
  `@Deprecated`. Do not add brittle reflection unless trivial.

---

## Step 6 — Documentation

### 6a. `agentos/agentos-factory-bridge-plugin/docs/factory-trust-boundary-migration.md`
- **Header / "In scope" table (section B):** change "19 tools" framing of exposed count
  from 8 to still 8 label but recount — the exposed tools go from 8 to **10**. Update the
  "### In scope — exposed by the new plugins (8)" heading to **(10)** and add two rows:
  | `FACTORY__start_workflow` | `FACTORY_WORKSTREAM__start_workflow` | `FactoryWorkstreamToolPlugin` |
  | `FACTORY__request_agent_retry` | `FACTORY_WORKSTREAM__request_agent_retry` | `FactoryWorkstreamToolPlugin` |
- **Deprecated list (section B "Command / transition tools — deprecated, NOT exposed"):**
  change the count from (11) to **(9)** and REMOVE `FACTORY__start_workflow` and
  `FACTORY__request_agent_retry` from the inline list, leaving: `transition_workflow`,
  `request_transition`, `request_human_decision`, `interrupt_attempt`,
  `propose_plan_change`, `publish_projection`, `provision_environment`,
  `record_agent_result`, `record_artifact`.
- Update the surrounding prose that says the boundary "exposes only the six reads and the
  two worker capabilities" → six reads + two Workstream commands + two worker capabilities.
- **Migration-path table:** update the
  `AgentConfig.integrations["FACTORY_WORKSTREAM"]` row to include the two new suffixes
  (`start_workflow`, `request_agent_retry`). In the "Command/transition suffixes …
  Deprecated — no longer exposed" row, remove `start_workflow` and `request_agent_retry`
  from that list.
- Add an explicit note documenting that: unblocking/skipping steps remains **human-only**
  in the Factory cockpit; `FACTORY_WORKSTREAM__request_agent_retry` only creates a
  `pending-human` request under a revision fence (the agent never decides the retry).

### 6b. `agentos/agentos-factory-bridge-plugin/README.md`
- In the "What it contributes" table, update the `FactoryWorkstreamToolPlugin` row to say
  the `FACTORY_WORKSTREAM` integration exposes **8 tools**: the six read-only
  `FACTORY_WORKSTREAM__*` views plus `FACTORY_WORKSTREAM__start_workflow` and governed
  `FACTORY_WORKSTREAM__request_agent_retry`.
- Where the README says both plugins "resolve through the ordinary ToolResolverService
  flow", add a short clause clarifying the Workstream boundary now also carries two
  boundary request commands while authority stays with the Factory.

---

## Out of scope (MUST remain `@Deprecated` / NOT exposed)
`request_transition`, `transition_workflow`, `interrupt_attempt`, `request_human_decision`,
`propose_plan_change`, `publish_projection`, `record_agent_result`, `record_artifact`,
`provision_environment`. Do NOT touch or reactivate these. Do NOT touch the `FACTORY_WORKER`
plugin or its two callbacks.

---

## Verification

Run from the `agentos/` composite build root:
```bash
cd agentos
./gradlew :agentos-factory-bridge-plugin:test
```
(The factory also runs the Nx-affected suite; this plan's work is Kotlin-only under the
Gradle composite, so the Gradle test task is the authoritative gate.)

Expected after changes:
- `FactoryGetWorkflowToolSpec` surface tests pass with 8 Workstream tools.
- `FactoryStartWorkflowToolSpec` and `FactoryCommandToolsTest` pass with new names.
- Grant-policy tests: the two reactivated tools evaluate `Neutral`; `FACTORY_WORKER__*`
  callbacks still deny without a binding.
- No out-of-scope tool name appears on either plugin surface.

### Acceptance criteria recap
- `FACTORY_WORKSTREAM` exposes exactly 8 tools, all prefixed `FACTORY_WORKSTREAM__*`.
- `start_workflow` and `request_agent_retry` functional; HTTP/trust/fence logic untouched.
- `request_agent_retry` output stays `pending-human` under the revision fence.
- No out-of-scope tools exposed; `FACTORY_WORKER` surface unchanged.
- `./gradlew :agentos-factory-bridge-plugin:test` green.
- Migration doc + README updated.

---

## Files touched (summary)
Main:
- `.../tools/FactoryStartWorkflowTool.kt` (remove @Deprecated, rename)
- `.../tools/FactoryRequestAgentRetryTool.kt` (remove @Deprecated, rename)
- `.../FactoryWorkstreamToolPlugin.kt` (wire 2 tools, KDoc + schema description)
- `.../FactoryToolGrantPolicy.kt` (KDoc only — verify no gating added)

Test:
- `.../FactoryStartWorkflowToolSpec.kt`
- `.../tools/FactoryCommandToolsTest.kt`
- `.../FactoryGetWorkflowToolSpec.kt` (surface + grant-policy tests to 8 tools, separation,
  not-exposed assertions)

Docs:
- `agentos/agentos-factory-bridge-plugin/docs/factory-trust-boundary-migration.md`
- `agentos/agentos-factory-bridge-plugin/README.md`
