# Spec - Task T2b: Extract Factory Bridge into standalone PF4J plugin `agentos-factory-bridge-plugin`

## 1. Overview & Objectives

The goal of Task T2b is to extract the Factory integration logic from `agentos-service` into a standalone PF4J plugin module located at `agentos/agentos-factory-bridge-plugin`.

The new plugin will hook into AgentOS core via the PF4J SPI extension points introduced in T2a (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`) as well as standard PF4J plugin extension classes / tools.

### Key Objectives:
1. **New Plugin Module Structure**:
   - `agentos/agentos-factory-bridge-plugin/`
   - Configured with `build.gradle.kts`, `settings.gradle.kts`, `project.json` (tags: `type:lib`, `platform:jvm`, `scope:plugin`).
   - Linked in `agentos/settings.gradle.kts` (`includeBuild("agentos-factory-bridge-plugin")`) and root `agentos/build.gradle.kts` (`deployPlugins` & `pluginBuilds` list).
   - Linked in `scripts/release.ts` if needed (note: release script manages `agentosService` and `agentosSdk` versions in catalog, which plugin builds consume).

2. **Source Code Implementation in `io.whozoss.agentos.plugins.factorybridge`**:
   - Class `FactoryBridgePlugin : Plugin(wrapper)`
   - PF4J `@Extension` class(es) implementing:
     - `ToolPlugin` (exposing `FACTORY__*` tools for `integrationType = "FACTORY"`)
     - `AnswerInterceptor` (handling answer interception/validation for checkpoints)
     - `CaseLifecycleObserver` (monitoring case status changes and event storage)
     - `ExternalExecutionContextProvider` (providing Factory context in session metadata)
     - `ToolGrantPolicy` (evaluating tool grants for Factory workflows)
   - Factory tools: `FactoryEnvironmentTool` (or `FactoryProvisionEnvironmentTool`), `FactoryGetWorkflowTool`, `FactoryPublishProjectionTool`, `FactoryRecordEvidenceTool` (or `FactoryRecordAgentResultTool` & `FactoryRecordArtifactTool`), `FactoryRequestHumanDecisionTool`, `FactoryRequestTransitionTool`, `FactoryStartWorkflowTool`, `FactorySubmitStepResultTool`, `FactoryTransitionWorkflowTool`.
   - Factory HTTP transport, binding services, structured step result submission, projection validation, tool grant service.

3. **Optionality & Coexistence Constraints**:
   - AgentOS core (`agentos-service`) must start and function cleanly without this plugin loaded.
   - DO NOT remove or modify existing Factory classes in `agentos-service` or `agentos-sdk` during T2b (cleaning is T2c). Coexistence is mandatory.
   - DO NOT touch files under `factory/**`.

4. **Verification Criteria**:
   - Run `./gradlew build` inside `agentos/` to ensure all modules (including `agentos-factory-bridge-plugin`) compile and pass unit tests.
   - Create unit/integration tests in `agentos/agentos-factory-bridge-plugin/src/test/...` testing tools, SPI extension hooks, and bindings.

---

## 2. Directory & Module Blueprint

### 2.1 File System Map
New files to be created under `agentos/`:

```
agentos/agentos-factory-bridge-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── project.json
└── src/
    ├── main/
    │   └── kotlin/
    │       └── io/
    │           └── whozoss/
    │               └── agentos/
    │                   └── plugins/
    │                       └── factorybridge/
    │                           ├── FactoryBridgePlugin.kt
    │                           ├── FactoryBridgeExtension.kt (or individual @Extension classes)
    │                           ├── FactoryStepResultBindingRegistry.kt
    │                           ├── FactoryStepResultBindingController.kt
    │                           ├── FactoryEnvironmentBindingService.kt
    │                           ├── FactoryCheckpointClient.kt
    │                           ├── FactoryToolGrantService.kt
    │                           ├── FactoryProjectionValidation.kt
    │                           ├── tools/
    │                           │   ├── FactoryEnvironmentTool.kt (FactoryProvisionEnvironmentTool)
    │                           │   ├── FactoryGetWorkflowTool.kt
    │                           │   ├── FactoryPublishProjectionTool.kt
    │                           │   ├── FactoryRecordEvidenceTool.kt (FactoryRecordAgentResultTool, FactoryRecordArtifactTool)
    │                           │   ├── FactoryRequestHumanDecisionTool.kt
    │                           │   ├── FactoryRequestTransitionTool.kt
    │                           │   ├── FactoryStartWorkflowTool.kt
    │                           │   ├── FactorySubmitStepResultTool.kt
    │                           │   └── FactoryTransitionWorkflowTool.kt
    │                           └── dto/
    └── test/
        └── kotlin/
            └── io/
                └── whozoss/
                    └── agentos/
                        └── plugins/
                            └── factorybridge/
                                ├── FactoryBridgeExtensionSpec.kt
                                ├── FactoryToolsSpec.kt
                                ├── FactoryStepResultBindingRegistrySpec.kt
                                └── FactoryCheckpointClientSpec.kt
```

### 2.2 Gradle & Nx Configuration Details

1. **`agentos/agentos-factory-bridge-plugin/settings.gradle.kts`**:
   ```kotlin
   rootProject.name = "agentos-factory-bridge-plugin"

   enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

   dependencyResolutionManagement {
       versionCatalogs {
           create("libs") {
               from(files("../gradle/libs.versions.toml"))
           }
       }
       repositories {
           mavenCentral()
           mavenLocal()
       }
   }

   includeBuild("../agentos-sdk")
   ```

2. **`agentos/agentos-factory-bridge-plugin/build.gradle.kts`**:
   Must include Kotlin JVM plugin, kapt for PF4J annotation processing, dependencies on `agentos-sdk`, `pf4j`, `jackson`, `okhttp3`, `klogger`, and testing libraries (`kotest`, `mockk`).
   Configured with legacy extension storage for kapt:
   ```kotlin
   kapt {
       arguments {
           arg("pf4j.storageClassName", "org.pf4j.processor.LegacyExtensionStorage")
       }
   }
   ```

3. **`agentos/agentos-factory-bridge-plugin/project.json`**:
   ```json
   {
     "name": "agentos-factory-bridge-plugin",
     "$schema": "../../node_modules/nx/schemas/project-schema.json",
     "projectType": "library",
     "sourceRoot": "agentos/agentos-factory-bridge-plugin/src",
     "tags": [
       "type:lib",
       "platform:jvm",
       "scope:plugin"
     ]
   }
   ```

4. **Root Registration**:
   - In `agentos/settings.gradle.kts`: add `includeBuild("agentos-factory-bridge-plugin")`.
   - In `agentos/build.gradle.kts`: add `"agentos-factory-bridge-plugin"` to the `pluginBuilds` list.

---

## 3. Detailed Component Implementation Details

### 3.1 Plugin & Extension Entrypoints
- **`FactoryBridgePlugin`**: Subclass of `org.pf4j.Plugin`, logging start/stop.
- **`FactoryBridgeExtension`** (or separate `@Extension` classes):
  - `@Extension` implementing `ToolPlugin`:
    - `override val integrationType = "FACTORY"`
    - `override val configSchema: JsonNode? = null`
    - `override fun provideTools(...)`: Returns `FACTORY__*` standard tools.
  - `@Extension` implementing `AnswerInterceptor`:
    - Intercepts answers to `QuestionEvent`s with checkpoint references.
    - Uses `FactoryCheckpointClient` to call Factory reply API before answer event is accepted.
  - `@Extension` implementing `CaseLifecycleObserver`:
    - Listens to `onStatusChanged` / `onEventStored` for case status changes and evidence/event sync to Factory if applicable.
  - `@Extension` implementing `ExternalExecutionContextProvider`:
    - `provideExecutionContext(...)`: Evaluates and returns Factory environment/session metadata map for sessionContext.
  - `@Extension` implementing `ToolGrantPolicy`:
    - `evaluateToolGrant(...)`: Filters tool grants based on Factory capability configuration.

### 3.2 Factory Tools & Supporting Domain Classes
- Port tool implementations from `agentos-service/src/main/kotlin/io/whozoss/agentos/factory/` into `agentos-factory-bridge-plugin`.
- Ensure tool names follow `FACTORY__*` conventions (e.g. `FACTORY__get_workflow`, `FACTORY__start_workflow`, etc.).
- Port HTTP transport, validation (`FactoryProjectionValidation`), and step result binding logic (`FactoryStepResultBindingRegistry`, `FactoryStepResultBindingController`).

### 3.3 Coexistence Rules
- Existing classes under `agentos-service/src/main/kotlin/io/whozoss/agentos/factory/` MUST remain untouched during this task.
- Zero modifications to `factory/**` in the root repository.

---

## 4. Execution Plan & Builder Instructions

### Step 1: Create Module Config Files
1. Create `agentos/agentos-factory-bridge-plugin/settings.gradle.kts`.
2. Create `agentos/agentos-factory-bridge-plugin/build.gradle.kts`.
3. Create `agentos/agentos-factory-bridge-plugin/project.json`.
4. Update `agentos/settings.gradle.kts` to include `includeBuild("agentos-factory-bridge-plugin")`.
5. Update `agentos/build.gradle.kts` `pluginBuilds` list to include `"agentos-factory-bridge-plugin"`.

### Step 2: Implement Source Code in Plugin
1. Create package `io.whozoss.agentos.plugins.factorybridge`.
2. Implement `FactoryBridgePlugin`.
3. Implement SPI extension classes (`@Extension`) for `ToolPlugin`, `AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, and `ToolGrantPolicy`.
4. Port Factory tool classes and support classes (`FactoryCheckpointClient`, `FactoryEnvironmentBindingService`, `FactoryStepResultBindingRegistry`, `FactoryProjectionValidation`, etc.) into the plugin package.

### Step 3: Implement Tests
1. Add unit tests for `FactoryBridgeExtension` and tools under `agentos/agentos-factory-bridge-plugin/src/test/...`.
2. Ensure SPI extension hooks and tool execution logic are covered.

### Step 4: Build & Verification
1. Run `./gradlew build` inside `agentos/` (or `./gradlew :agentos-factory-bridge-plugin:build`).
2. Run `./gradlew deployPlugins` inside `agentos/` to verify plugin JAR generation and deployment.
3. Verify all existing tests in `agentos-service` and `agentos-sdk` continue to pass.

---

## 5. Verification Command Summary
- Compile and test new plugin: `cd agentos && ./gradlew :agentos-factory-bridge-plugin:build`
- Full Gradle build: `cd agentos && ./gradlew build`
- Deploy plugins check: `cd agentos && ./gradlew deployPlugins`
