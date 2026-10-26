# Implementation Plan - Execution Plugin Selection Contract (Factory Generic Lot A)

This plan defines the exact modifications, model additions, validation logic, SPI interface, PF4J resolution check, and test coverage needed to add the execution plugin selection contract to Factory generic.

## Scope & Constraints
- **Strictly in scope**: Top-level `execution: { "plugin": "<id>" }` definition contract, canonical hashing, frozen execution policy model & projection propagation, PF4J SPI interface creation in `factory-sdk`, extension implementation in `factory-forge-plugin`, and plugin resolution check before external side effects during workflow start/execution in `factory-service`.
- **Strictly out of scope**: Case hierarchy (`rootCaseId`/`parentCaseId`), `CapabilityExecutionService.kt`, AgentOS adapter, step results, UI components, deleted or non-existent files.
- **Backward compatibility requirement**: Workflows without an `execution` field MUST maintain identical canonical hashes and runtime behavior as historically produced.

---

## 1. Extension of Workflow Definition (`WorkflowDefinition.kt`)

### File to touch:
`factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowDefinition.kt`

### Detailed Changes:
1. Update `DEFINITION_FIELDS`:
   ```kotlin
   private val DEFINITION_FIELDS = setOf(
       "schemaVersion", "workflowType", "version", "title",
       "trustedExecution", "execution", "steps"
   )
   ```
2. Define `EXECUTION_FIELDS`:
   ```kotlin
   private val EXECUTION_FIELDS = setOf("plugin")
   ```
3. In `WorkflowDefinitionValidator.validate(input: Any?)`:
   - Add validation logic for top-level `execution` field when present and non-null:
     ```kotlin
     var execution: Map<String, Any?>? = null
     if (record.containsKey("execution") && record["execution"] != null) {
         val rawExecution = record["execution"]
         if (rawExecution !is Map<*, *>) {
             return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "execution")
         }
         val execRecord = rawExecution.entries.associate { it.key.toString() to it.value }
         if (execRecord.keys.any { it !in EXECUTION_FIELDS }) {
             return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "execution")
         }
         val (plugin, pluginError) = text(execRecord["plugin"], "execution.plugin", safe = true, maximum = 128)
         if (pluginError != null) return WorkflowDefinitionValidation.Invalid(pluginError)
         execution = mapOf("plugin" to plugin!!)
     }
     ```
   - In normalization step:
     If `execution != null`, set `normalized["execution"] = execution`.
     (When `execution` is absent/null, omit `normalized["execution"]` or keep key unpopulated to preserve exact canonical hash for existing definitions).

---

## 2. Domain Models & Canonical Hash (`WorkflowModels.kt`, `WorkflowInstance.kt`, `CanonicalHash.kt`)

### Files to touch:
- `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`
- `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowInstance.kt`
- `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/CanonicalHash.kt`

### Detailed Changes:

#### A. `WorkflowModels.kt`
1. Define execution policy model:
   ```kotlin
   data class WorkflowExecutionPolicy(
       val plugin: String,
   ) {
       fun toJson(): Map<String, Any?> = mapOf("plugin" to plugin)
   }
   ```
2. Update `WorkflowDefinitionInput`:
   Add optional `executionPolicy: WorkflowExecutionPolicy? = null` or `execution: Map<String, Any?>? = null`.
   ```kotlin
   data class WorkflowDefinitionInput(
       val workflowType: String,
       val version: String,
       val definitionHash: String,
       val steps: List<WorkflowStepDefinition>,
       val executionPolicy: WorkflowExecutionPolicy? = null,
   )
   ```

#### B. `CanonicalHash.kt`
1. Ensure `CanonicalHash.canonicalize(value)` handles maps and nested maps recursively with key sorting (it already recursively sorts map keys naturally).
2. Ensure `CanonicalHash.workflowStartCommandHash(command, definition)` includes `execution` / `executionPolicy` when present, while preserving identical digest when absent.

#### C. `WorkflowInstance.kt`
1. In `createWorkflowInstance`:
   - If `definition.executionPolicy != null`, add `"execution" to definition.executionPolicy.toJson()` to `instance` and `"execution" to definition.executionPolicy.toJson()` to `projection`.
   - If absent, do not set `"execution"` key (or set to `null` if consistent with definition behavior, ensuring canonical hash backward compatibility).

---

## 3. PF4J SPI Interface in SDK & Forge Extension Implementation

### Files to create:
1. `factory/factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactoryWorkflowExecutionPolicy.kt`
2. `factory/factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/plugin/ForgeWorkflowExecutionPolicy.kt`

### Detailed Changes:

#### A. `FactoryWorkflowExecutionPolicy.kt` (in `factory-sdk`)
```kotlin
package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

interface FactoryWorkflowExecutionPolicy : ExtensionPoint {
    fun getPluginId(): String
}
```

#### B. `ForgeWorkflowExecutionPolicy.kt` (in `factory-forge-plugin`)
```kotlin
package io.whozoss.factory.forge.plugin

import io.whozoss.factory.sdk.spi.FactoryWorkflowExecutionPolicy
import org.pf4j.Extension

@Extension
class ForgeWorkflowExecutionPolicy : FactoryWorkflowExecutionPolicy {
    override fun getPluginId(): String = "forge"
}
```

---

## 4. PF4J Plugin Resolution in Workflow Service (`WorkflowService.kt`)

### File to touch:
`factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`

### Detailed Changes:
1. Inject optional `pluginManager: PluginManager? = null` into `WorkflowService` constructor (or inject plugin resolution provider/helper).
2. Add explicit validation method `resolveAndValidateExecutionPlugin(pluginId: String)`:
   ```kotlin
   private fun resolveAndValidateExecutionPlugin(pluginId: String) {
       val manager = pluginManager
       val extensions = manager?.getExtensions(FactoryWorkflowExecutionPolicy::class.java).orEmpty()
       val matchingExtension = extensions.firstOrNull { it.getPluginId() == pluginId }
       
       if (matchingExtension == null) {
           // Also check direct plugin lookup if pluginId matches PF4J plugin id
           val pluginWrapper = manager?.getPlugin(pluginId)
           if (pluginWrapper == null && extensions.none { it.getPluginId() == pluginId }) {
               throw workflowException(
                   WorkflowErrorCodes.INVALID_START_REQUEST, // or WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND
                   "Execution plugin '$pluginId' was not found or is disabled."
               )
           }
       }
   }
   ```
3. In `WorkflowService.start(...)` (or run/submission methods prior to external side effects):
   - Extract execution policy/plugin from `definitionRecord` or `definitionInput`.
   - If an `execution.plugin` is declared (e.g. `plugin = "forge"`):
     Call `resolveAndValidateExecutionPlugin(pluginId)` BEFORE database insertion or runner queue submission.

---

## 5. Verification & Test Plan

### Files to touch/create:
- `factory/factory-service/src/test/kotlin/io/whozoss/factory/workflow/domain/WorkflowDefinitionValidatorTest.kt`
- `factory/factory-service/src/test/kotlin/io/whozoss/factory/workflow/domain/CanonicalHashTest.kt`
- `factory/factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowServiceTest.kt` or integration tests

### Test Scenarios:
1. `WorkflowDefinitionValidatorTest.kt`:
   - Valid definition with `{ "execution": { "plugin": "forge" } }` -> passes validation, normalized map contains `execution`.
   - Definition without `execution` -> passes validation, normalized map unchanged.
   - Invalid `execution` field (e.g. non-map, unknown sub-key `{ "plugin": "forge", "invalid": true }`, empty/blank plugin string, non-string plugin) -> rejected with `INVALID_VALUE` and correct path `execution` / `execution.plugin`.
2. `CanonicalHashTest.kt`:
   - Verify canonical hash stability for legacy definitions without `execution`.
   - Verify distinct, deterministic canonical hash for definitions including `{ "execution": { "plugin": "forge" } }`.
3. `WorkflowServiceTest` / Plugin resolution tests:
   - Starting a workflow declaring `plugin = "forge"` when plugin is present -> succeeds.
   - Starting a workflow declaring `plugin = "missing-plugin"` when plugin is missing -> rejected with explicit `WorkflowException` ("Execution plugin 'missing-plugin' was not found or is disabled.").
   - Ensure existing tests (`SessionDefinitionCatalogTest`, `CanonicalHashTest`, `WorkflowDefinitionValidatorTest`) pass.

---

## Verification Commands
To be run after implementation:
- `pnpm nx test factory-sdk`
- `pnpm nx test factory-forge-plugin`
- `pnpm nx test factory-service`
