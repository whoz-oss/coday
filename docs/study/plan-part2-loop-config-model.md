# Loop-Config Data Model and Runtime Redesign — Implementation Plan

> Governed by ADR-64 US 4, decision 4 (strictly linear runtime, no control flow, fan-out is a batch tool).
> Read `agentos-scheduler-agentloop-convergence.md` first — this document does not relitigate those decisions.

---

## 1. The new config shape

### 1.1 Kotlin data classes

The new model replaces the two-field `{search, act}` payload with a versioned, ordered list of steps. Every step is a tool invocation. Input mapping is declared by reference; evaluation is hardcoded for now.

```kotlin
// agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/workflow/LoopConfig.kt
package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * A versioned, strictly-linear loop configuration.
 *
 * @param version   Config schema version. Currently must be 1.
 * @param steps     Ordered list of tool steps. Executed top-to-bottom with no branching.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopConfig(
    val version: Int = 1,
    val steps: List<LoopStep>,
)

/**
 * One step in a [LoopConfig].
 *
 * @param id        Stable identifier for this step, referenced by [LoopStepInput.from].
 * @param tool      Name of the tool to invoke (must be in the agent's resolved tool set).
 * @param input     How to build the tool's input JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopStep(
    val id: String,
    val tool: String,
    val input: LoopStepInput,
)

/**
 * Input mapping for one step.
 *
 * When [from] is null the step uses [params] verbatim as its input JSON.
 * When [from] is set the runtime merges the referenced step's [ToolExecutionResult.structuredOutput]
 * under the key `"previousStepOutput"` into [params], producing the final input JSON.
 * The JsonPath evaluation specified in ADR-64 replaces only this merge without reshaping
 * the model.
 *
 * @param params    Static input parameters, passed verbatim to the tool.
 * @param from      Id of a preceding step whose structuredOutput is merged into [params].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopStepInput(
    val params: JsonNode? = null,
    val from: String? = null,
)
```

### 1.2 JSON example

This is the canonical form for the scheduler's default config (list audience → fan-out):

```json
{
  "version": 1,
  "steps": [
    {
      "id": "list",
      "tool": "ListAgentUsers",
      "input": {
        "params": { "agentName": "talent-analyzer" }
      }
    },
    {
      "id": "fanout",
      "tool": "BatchCaseStarter",
      "input": {
        "params": {
          "agentName": "talent-analyzer",
          "prompt": "Analyse this entity: {entityId}"
        },
        "from": "list"
      }
    }
  ]
}
```

When `from: "list"` is evaluated, the runtime serialises the `list` step's `structuredOutput` into a `"previousStepOutput"` key and merges it into `params`. `BatchCaseStarter` reads `previousStepOutput.data` (the user list) and iterates.

### 1.3 Mapping the current `{search, act}` payload

The existing payload:
```json
{
  "search": { "tool": "SearchTalents", "params": { "endDatePeriod": ["THIS_WEEK"] } },
  "act": { "agentName": "talent-analyzer", "prompt": "Analyse: {entityId}" }
}
```

becomes:
```json
{
  "version": 1,
  "steps": [
    {
      "id": "search",
      "tool": "SearchTalents",
      "input": { "params": { "endDatePeriod": ["THIS_WEEK"] } }
    },
    {
      "id": "act",
      "tool": "BatchCaseStarter",
      "input": {
        "params": { "agentName": "talent-analyzer", "prompt": "Analyse: {entityId}" },
        "from": "search"
      }
    }
  ]
}
```

The hardcoded merge puts the search tool's `structuredOutput` (the list of entities) under `previousStepOutput`. `BatchCaseStarter` reads `previousStepOutput.data` to get the entities and `previousStepOutput.metadata.next` to detect pagination.

---

## 2. Deletions

### 2.1 Files deleted

| File | Reason |
|------|--------|
| `agentos-service/src/main/kotlin/io/whozoss/agentos/workflow/SearchResult.kt` | `SearchResult`, `SearchResultItem`, `SearchResultTarget`, `SearchResultMetadata` are Whoz-specific types that the runtime no longer parses. The contract between steps is now each tool's `outputSchema()` / `structuredOutput`. |
| `agentos-service/src/main/kotlin/io/whozoss/agentos/workflow/AgentLoopPayload.kt` | `AgentLoopPayload`, `AgentLoopSearch`, `AgentLoopAct` are replaced by `LoopConfig` / `LoopStep` / `LoopStepInput`. |

### 2.2 Call sites that must change

**`LoopWorkflowRunnerService`** — the entire class is rewritten (see §4). Remove:
- `run(payload: AgentLoopPayload, ...)` — signature changes.
- `search(...)` private method — special-cased search phase gone.
- `act(item: SearchResultItem, ...)` private method — gone.
- `startOutcome(...)` — moved into `BatchCaseStarter`.
- `SearchOutcome` sealed interface — gone.
- `ItemOutcome` sealed interface — moves into `BatchCaseStarter` (see §3).
- `activeContextOf(item)` — see §2.3 below.

**`AgentLoop`** — `resolvePayload()` deserializes `AgentLoopPayload`; it must instead deserialize `LoopConfig`. The guard `if (payload.search.tool.isBlank())` is replaced by `require(config.steps.isNotEmpty())`. The log line referencing `payload.search.tool` / `payload.act.agentName` is replaced.

**`AgentConfigNode.toDomain()`** — `loopConfigJson?.let { MAPPER.readTree(it) }` is unchanged (still a `JsonNode`). No migration needed in the node itself.

**`AgentServiceImpl.createAgentInstance` (LOOP branch)** — passes `loopConfig: JsonNode?` to `AgentLoop`; this is unchanged.

**`LoopWorkflowRunnerUnitSpec`** — the test fixtures building `AgentLoopPayload` / `AgentLoopSearch` / `AgentLoopAct` / `SearchResult` must be rewritten in terms of `LoopConfig` and `BatchCaseStarter` (see §7).

**`AgentLoopUnitSpec`** — the `validPayloadJson` fixture and `resolvePayload` tests must be updated to the new format.

### 2.3 The `activeContextOf` responsibility

`LoopWorkflowRunnerService.activeContextOf(item)` builds the Whoz-specific `activeContext` shape:
```kotlin
mapOf("activeContext" to listOf(mapOf("type" to item.entityType, "data" to mapOf("id" to item.entityId))))
```

This is Whoz's format for "the entity the prompt is about". **It moves entirely into `BatchCaseStarter`'s input mapping logic.** The fan-out tool receives the raw entity row from the search step's `structuredOutput` and constructs `sessionContext` itself — the runtime never touches it. The runtime's job ends at merging `previousStepOutput` into `params`; what the tool does with those params is its own business.

This is not just a code move: the responsibility now belongs to the **tool's input schema** (what keys it expects in `params`) rather than to the runtime. A different tool with a different context shape requires no runtime change.

---

## 3. The batch / fan-out tool

### 3.1 Specification

**Name:** `BatchCaseStarter`  
**Location:** `agentos-service/src/main/kotlin/io/whozoss/agentos/casePlugin/BatchCaseStarterTool.kt`  
**Implements:** `StandardTool<BatchCaseStarterTool.Input>`

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "agentName":   { "type": "string" },
    "prompt":      { "type": "string" },
    "previousStepOutput": {
      "type": "object",
      "description": "structuredOutput of the preceding step. Must contain a 'data' array of entity rows.",
      "properties": {
        "data": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "entityId": { "type": "string" },
              "entityType": { "type": "string" },
              "targets": {
                "type": "array",
                "items": {
                  "type": "object",
                  "properties": {
                    "entityType": { "type": "string" },
                    "entityId":   { "type": "string" }
                  }
                }
              }
            },
            "required": ["entityId"]
          }
        }
      },
      "required": ["data"]
    }
  },
  "required": ["agentName", "prompt", "previousStepOutput"]
}
```

**Output schema** (`outputSchema()` returns):
```json
{
  "type": "object",
  "properties": {
    "started":       { "type": "integer" },
    "unknownUser":   { "type": "integer" },
    "noAgentAccess": { "type": "integer" },
    "failed":        { "type": "integer" },
    "startedCaseIds": { "type": "array", "items": { "type": "string", "format": "uuid" } }
  },
  "required": ["started", "unknownUser", "noAgentAccess", "failed", "startedCaseIds"]
}
```

**Kotlin input class:**
```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class Input(
    val agentName: String,
    val prompt: String,
    val previousStepOutput: JsonNode,
)
```

### 3.2 Cap enforcement

The cap (`LimitsConfigProperties.agentLoopMaxItems`) is injected at construction. If `previousStepOutput.data.size > cap`, the tool returns `ToolExecutionResult.error(...)` with `errorType = "OVER_BATCH_CAP"`. It **never truncates silently**. The error message must include the actual count and the cap value:

```
"BatchCaseStarter: input contains 200 entities but the cap is 50. Reduce the search result set or raise the cap."
```

This is parity trap #3 from the ADR: "Must fail loudly, never truncate."

### 3.3 Delegation to `CaseStarterService`

The tool holds a reference to `FreeCaseStarterForUserTool` resolved from `ToolContext` at execute time — **not** a direct reference to `CaseStarterService`. It calls `FreeCaseStarterForUserTool` via `starterTool.executeWithJson(...)` exactly as `LoopWorkflowRunnerService.act(...)` does today. The authorization gate (`CaseStarterService.isAdmin`, `TARGET_USER_NOT_FOUND`, `AGENT_NOT_AVAILABLE`) is unchanged and stays inside `CaseStarterService`.

The tool finds its starter tool by scanning `ToolContext.caseEvents`... wait — `ToolContext` does not carry the sibling tools. The tool must receive the starter tool at construction time, injected by the runtime (see §4.2). The runtime passes `starterTool` into `BatchCaseStarterTool` when it resolves the tool set for the step.

**Alternative (simpler):** `BatchCaseStarterTool` is NOT a `StandardTool` registered in the integration registry. It is constructed directly by `LoopWorkflowRunnerService` for each run, receiving the resolved `FreeCaseStarterForUserTool` at construction. This avoids the need to thread tools through `ToolContext` and keeps the pattern identical to today's `act(...)` method.

The `activeContext` shaping (§2.3) is performed here: for each entity row, the tool reads `entityId` and `entityType` from `previousStepOutput.data[i]`, constructs:
```kotlin
mapOf("activeContext" to listOf(mapOf("type" to entityType, "data" to mapOf("id" to entityId))))
```
and passes it as `sessionContext` to `FreeCaseStarterForUserTool.Input`.

### 3.4 Per-element outcome reporting

The tool iterates entities and collects per-element outcomes using the same `ItemOutcome` sealed interface currently in `LoopWorkflowRunnerService` (moved here). The final `structuredOutput` contains the aggregated counts. The textual `output` mirrors today's `LoopRunOutcome.Completed.summary()`.

### 3.5 Reuse vs. new tool

`FreeCaseStarterForUserTool` starts **one** case for a named user. `BatchCaseStarter` starts **N** cases by iterating a list, resolving users via `UserService`, and delegating each start to `FreeCaseStarterForUserTool`. They solve different problems and must stay separate tools. `BatchCaseStarter` is not registered as a `ToolPlugin` integration (it is not user-configurable); it is constructed by the runtime for LOOP agents that need it.

---

## 4. The runtime

### 4.1 New `LoopWorkflowRunnerService.run` signature

```kotlin
suspend fun run(
    config: LoopConfig,
    context: LoopRunContext,
    shouldContinue: () -> Boolean,
): LoopRunOutcome
```

The `AgentLoopPayload` parameter is replaced by `LoopConfig`.

### 4.2 Flat step executor

The new `run(...)` body:

```kotlin
val triggerUser = context.triggerUser
    ?: return LoopRunOutcome.Aborted("An AgentLoop must be triggered by an identified user.")

val stepOutputs = mutableMapOf<String, JsonNode>() // stepId → structuredOutput

for (step in config.steps) {
    if (!shouldContinue()) return LoopRunOutcome.Interrupted

    val tool = context.tools.find { it.name == step.tool }
        ?: return LoopRunOutcome.Aborted("Tool '${step.tool}' (step '${step.id}') is not available to agent '${context.agentName}'.")

    // Build input JSON: merge previous step output under 'previousStepOutput' if declared
    val inputJson = buildInputJson(step.input, stepOutputs)

    val toolContext = toolContext(context, triggerUser)
    val requestId = UUID.randomUUID().toString()

    // Emit ToolRequestEvent into the case transcript (ADR-64 decision 6)
    context.emitEvent(ToolRequestEvent(
        namespaceId = context.namespaceId,
        caseId = context.caseId,
        toolRequestId = requestId,
        toolName = step.tool,
        args = inputJson,
    ))

    val result = try {
        tool.executeWithJson(inputJson, toolContext)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "[LoopRunner] step '${step.id}' tool '${step.tool}' threw" }
        context.emitEvent(ToolResponseEvent(
            namespaceId = context.namespaceId, caseId = context.caseId,
            toolRequestId = requestId, toolName = step.tool,
            output = MessageContent.Text("Step '${step.id}' failed: ${e.message}"),
            success = false,
        ))
        return LoopRunOutcome.Aborted("Step '${step.id}' (tool '${step.tool}') threw: ${e.message}")
    }

    context.emitEvent(ToolResponseEvent(
        namespaceId = context.namespaceId, caseId = context.caseId,
        toolRequestId = requestId, toolName = step.tool,
        output = MessageContent.Text(result.output),
        success = result.success,
        structuredOutput = result.structuredOutput,
    ))

    if (!result.success) {
        return LoopRunOutcome.Aborted("Step '${step.id}' (tool '${step.tool}') failed: ${result.output}")
    }

    result.structuredOutput?.let { stepOutputs[step.id] = it }
}

return LoopRunOutcome.Completed
```

The `CancellationException`-before-generic-handler discipline is visible in the `catch` block: `CancellationException` is re-thrown before the generic `Exception` handler, preventing coroutine cancellation from being swallowed.

### 4.3 `emitEvent` in `LoopRunContext`

`LoopRunContext` must gain an `emitEvent` lambda so the runner can write events into the case transcript:

```kotlin
data class LoopRunContext(
    val namespaceId: UUID,
    val caseId: UUID,
    val agentName: String,
    val triggerUser: User?,
    val tools: Collection<StandardTool<*>>,
    val caseEvents: List<CaseEvent>,
    val emitEvent: (CaseEvent) -> Unit,  // NEW
)
```

`AgentLoop` already collects events into its flow; it passes `emit` (from `flow { ... }`) as `emitEvent`. The `ToolRequestEvent` / `ToolResponseEvent` emitted by the runner are the step-level ledger — they are persisted, replayed, and visible in the case transcript, satisfying ADR-64 decision 6.

### 4.4 `buildInputJson`

```kotlin
private fun buildInputJson(
    input: LoopStepInput,
    stepOutputs: Map<String, JsonNode>,
): String {
    val base = input.params ?: objectMapper.createObjectNode()
    if (input.from == null) return objectMapper.writeValueAsString(base)
    val prev = stepOutputs[input.from]
        ?: throw IllegalStateException("Step references '${input.from}' but it produced no structured output.")
    val merged = (base as ObjectNode).deepCopy<ObjectNode>()
    merged.set<JsonNode>("previousStepOutput", prev)
    return objectMapper.writeValueAsString(merged)
}
```

This is the hardcoded evaluation. The later JsonPath work replaces this function body without touching any other part of the model.

### 4.5 `LoopRunOutcome` becomes

```kotlin
sealed interface LoopRunOutcome {
    /** The run could not proceed; [reason] is shown to the user. */
    data class Aborted(val reason: String) : LoopRunOutcome

    /** All steps completed successfully. */
    data object Completed : LoopRunOutcome

    /** The run was interrupted by shouldContinue() returning false. */
    data object Interrupted : LoopRunOutcome
}
```

The rich `Completed` fields (`returned`, `startedCaseIds`, `unknownUser`, etc.) move into `BatchCaseStarterTool`'s `structuredOutput` and `output` string. `AgentLoop` now emits a summary `MessageEvent` whose content is the last step's tool `output` string (the batch tool's summary), rather than calling `outcome.summary()`. For `Aborted` it still emits a `WarnEvent`. For `Interrupted` it emits only `AgentFinishedEvent`.

---

## 5. Write-time validation of `AgentConfig.loopConfig`

### 5.1 Current state

`AgentConfigServiceImpl.create(entity)` and `update(entity)` call `agentConfigRepository.save(entity)` with zero validation of `loopConfig`. `AgentConfigNode.fromDomain` serializes it as `MAPPER.writeValueAsString(it)` — any `JsonNode` is accepted. Parsing happens for the first time in `AgentLoop.resolvePayload()` at run time.

### 5.2 Validation in `AgentConfigServiceImpl`

Add a private `validateLoopConfig(entity: AgentConfig)` method called from both `create` and `update` when `entity.resolvedExecutionMode == ExecutionMode.LOOP`:

```kotlin
private fun validateLoopConfig(entity: AgentConfig) {
    val json = entity.loopConfig
        ?: throw IllegalArgumentException(
            "AgentConfig '${entity.name}' has executionMode=LOOP but no loopConfig."
        )
    val config = try {
        objectMapper.treeToValue(json, LoopConfig::class.java)
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "AgentConfig '${entity.name}' loopConfig cannot be parsed as LoopConfig: ${e.message}", e
        )
    }
    require(config.version == 1) {
        "AgentConfig '${entity.name}' loopConfig.version must be 1 (got ${config.version})."
    }
    require(config.steps.isNotEmpty()) {
        "AgentConfig '${entity.name}' loopConfig.steps must not be empty."
    }
    // Validate step ids are unique
    val ids = config.steps.map { it.id }
    require(ids.size == ids.toSet().size) {
        "AgentConfig '${entity.name}' loopConfig has duplicate step ids: $ids."
    }
    // Validate 'from' references an earlier step (not itself, not a future step)
    val seen = mutableSetOf<String>()
    for (step in config.steps) {
        step.input.from?.let { ref ->
            require(ref in seen) {
                "AgentConfig '${entity.name}' step '${step.id}' references '${ref}' which does not precede it."
            }
        }
        // Validate tool names resolve against the agent's declared integrations
        // (best-effort: only possible when integrations are non-null)
        entity.integrations?.let { integrations ->
            require(step.tool in integrations || step.tool == "BatchCaseStarter") {
                "AgentConfig '${entity.name}' step '${step.id}' tool '${step.tool}' " +
                    "is not in the agent's integrations map."
            }
        }
        seen += step.id
    }
}
```

The cap check ("declared caps sit under the platform cap") is **not** done here — the cap is a runtime concern (`LimitsConfigProperties.agentLoopMaxItems`) and `AgentConfigServiceImpl` does not depend on `LimitsConfigProperties`. The `BatchCaseStarterTool` enforces it at execute time (§3.2).

`AgentConfigServiceImpl` must gain an `ObjectMapper` constructor parameter. It currently has none; add it as a `@Autowired` Spring injection.

### 5.3 Existing persisted `loopConfig` payloads

Existing payloads use the `{search, act}` shape (version absent). They are **not migrated automatically**. The approach is version-tolerant parsing:

- `AgentConfigNode.toDomain()` continues to deserialize `loopConfigJson` as a raw `JsonNode` — no change.
- `AgentLoop.resolvePayload()` first tries to parse as `LoopConfig` (version field present). If that fails, it tries the legacy `AgentLoopPayload` shape and **synthesizes** a `LoopConfig` from it using a `LegacyPayloadMigrator` helper:

```kotlin
internal fun AgentLoopPayload.toLoopConfig(): LoopConfig = LoopConfig(
    version = 1,
    steps = listOf(
        LoopStep(
            id = "search",
            tool = search.tool,
            input = LoopStepInput(params = search.params),
        ),
        LoopStep(
            id = "act",
            tool = "BatchCaseStarter",
            input = LoopStepInput(
                params = objectMapper.createObjectNode().apply {
                    put("agentName", act.agentName)
                    put("prompt", act.prompt)
                },
                from = "search",
            ),
        ),
    ),
)
```

This means existing agents keep working without a data migration. A background migration script (outside this plan's scope) can update persisted `loopConfigJson` to the new shape and remove the legacy parsing path later.

Write-time validation (§5.2) only runs on create/update after this change ships, so existing nodes are never rejected retroactively.

---

## 6. The Agent contract

### 6.1 Current state

`Agent` in `agentos-sdk`:
```kotlin
interface Agent : Entity {
    val name: String
    val llmProvider: String  // non-nullable
    val llmModel: String     // non-nullable
    ...
}
```

`AgentLoop` satisfies this with sentinels:
```kotlin
override val llmProvider: String = PROVIDER_NAME  // "none"
override val llmModel: String = MODEL_NAME         // "agent-loop"
```

### 6.2 Change

Make both fields nullable in `Agent`:

```kotlin
// agentos-sdk: Agent.kt
interface Agent : Entity {
    val name: String
    val llmProvider: String?  // null = no LLM used
    val llmModel: String?     // null = no LLM used
    ...
}
```

### 6.3 Knock-on changes

There are exactly three implementations, all in `agentos-service`:

**`AgentLoop`** — remove the sentinel constants and the `override` fields, or override with `null`:
```kotlin
override val llmProvider: String? = null
override val llmModel: String? = null
```
Remove `PROVIDER_NAME` / `MODEL_NAME` companion constants. Update `AgentLoopUnitSpec` test `"llmProvider and llmModel are sentinel values signalling no LLM usage"` to assert `null`.

**`AgentSimple`** and **`AgentAdvanced`** — their `llmProvider` / `llmModel` are set from `providerConfig.name` / `modelConfig.apiModelName` (both `String`, non-null) at construction. Change their property declarations to `String?` to satisfy the interface; no logic changes needed.

**Consumers of `llmProvider` / `llmModel`** — search for usages in `agentos-service`:
- `AgentFinishedEvent` and `AgentRunningEvent` already declare both fields as `String?`. No change.
- `AgentServiceImpl.createAgentInstance` passes `providerConfig.name` (non-null `String`) and `modelConfig.apiModelName` (non-null `String`) to `AgentSimple` / `AgentAdvanced`. No change.
- Any code reading `agent.llmProvider` / `agent.llmModel` must handle null (add `?.let { }` or `?: "none"` where a string is required downstream).

---

## 7. Tests

### 7.1 Existing spec files to extend

**`AgentLoopUnitSpec`**  
Path: `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/agent/AgentLoopUnitSpec.kt`

Changes:
- Replace `validPayloadJson` fixture with a `LoopConfig` JSON (two steps).
- Update `"payload from loopConfig is passed to the runner with the case context"` — capture `LoopConfig` instead of `AgentLoopPayload`.
- Update `"no loopConfig emits a WarnEvent"` and `"invalid loopConfig emits a WarnEvent"` — still valid, adjust fixture.
- Add: `"legacy {search,act} loopConfig is accepted and synthesized into LoopConfig"` — pass old-format JSON, verify run proceeds.
- Update: `"llmProvider and llmModel are sentinel values"` → assert `null` instead of `PROVIDER_NAME` / `MODEL_NAME`.
- Add: `"emits ToolRequestEvent and ToolResponseEvent for each step"` — verify the transcript events emitted via `emitEvent` lambda.

**`LoopWorkflowRunnerUnitSpec`**  
Path: `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/workflow/LoopWorkflowRunnerUnitSpec.kt`

This spec is almost entirely rewritten since the runner's contract changes:
- Replace `AgentLoopPayload` fixtures with `LoopConfig` fixtures.
- Remove all SEARCH-phase tests (search is now just a tool step, tested via the generic step execution path).
- Remove `SearchResult`-parsing tests.
- Add: `"aborts when a step's tool is not in the agent's tool set"` — verify `Aborted` with clear message.
- Add: `"merges previous step's structuredOutput under previousStepOutput key when from is set"` — verify `buildInputJson`.
- Add: `"re-throws CancellationException from a step tool"` — verify coroutine cancellation propagates.
- Add: `"emits ToolRequestEvent before and ToolResponseEvent after each step"` — verify the `emitEvent` lambda is called in order.
- Add: `"returns Interrupted when shouldContinue is false between steps"`.
- Add: `"returns Aborted when a step tool returns success=false"`.
- Keep: triggering user guard test (`"aborts without a triggering user"`).

**`AgentServiceImplUnitSpec`**  
Path: `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/agent/AgentServiceImplUnitSpec.kt`

Changes:
- `"findAgentByName with executionMode=LOOP returns an AgentLoop without calling getChatClient"` — still valid, no change needed.
- Add: `"AgentLoop.llmProvider and llmModel are null"` — verify the nullable contract.

### 7.2 New spec file for `BatchCaseStarterTool`

Create `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/casePlugin/BatchCaseStarterToolUnitSpec.kt`.

Key cases to cover:
- Fails loudly (`OVER_BATCH_CAP`) when entity count exceeds cap.
- Iterates all entities and delegates each to `FreeCaseStarterForUserTool`.
- Counts `unknownUser`, `noAgentAccess`, `failed`, `started` correctly.
- Constructs `activeContext` sessionContext correctly from `entityId` / `entityType`.
- Returns aggregated `structuredOutput` with correct counts.
- `CancellationException` from the starter tool propagates.

### 7.3 New spec file for write-time validation

Create `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/agentConfig/AgentConfigLoopValidationUnitSpec.kt`.

Key cases:
- `create` with `LOOP` mode and valid `LoopConfig` succeeds.
- `create` with `LOOP` mode and null `loopConfig` throws `IllegalArgumentException`.
- `create` with `LOOP` mode and unparseable `loopConfig` throws.
- `create` with `LOOP` mode and duplicate step ids throws.
- `create` with `LOOP` mode and forward `from` reference throws.
- `update` applies the same validation.
- `create` with `SIMPLE` mode and any `loopConfig` does not validate it (ignored).
