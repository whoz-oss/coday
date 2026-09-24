# Spec - Task T2b: Extract Factory Bridge into standalone PF4J plugin `agentos-factory-bridge-plugin`

## 1. Overview & Objectives
The goal of Task T2b is to extract the Factory integration logic from `agentos-service` into a standalone PF4J plugin module located at `agentos/agentos-factory-bridge-plugin`.

The new plugin will hook into AgentOS core via the PF4J SPI extension points introduced in T2a (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`) as well as standard PF4J plugin extension classes / tools.

### Key Objectives:
1. **New Plugin Module Structure**:
   - `agentos/agentos-factory-bridge-plugin/`
   - Configured with `build.gradle.kts`, `settings.gradle.kts`, `project.json`.
   - Linked in `agentos/settings.gradle.kts` (`includeBuild("agentos-factory-bridge-plugin")`) and root `agentos/build.gradle.kts` (`deployPlugins` & plugin builds list).
2. **Move & Adapt Core Factory Logic**:
   - Package: `io.whozoss.agentos.plugins.factorybridge`
   - Transfer Factory tools (`FACTORY__*`), binding services, client HTTP logic, and validation helpers.
   - Implement PF4J `@Extension` class(es) providing SPI extension hooks (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`, `ToolPlugin`).
3. **Optionality & Coexistence**:
   - Core `agentos-service` must compile and operate cleanly without the plugin present or loaded.
   - Core Factory classes in `agentos-service` / `agentos-sdk` MUST NOT be removed yet (removal happens in T2c). Coexistence is explicitly required for T2b.
   - DO NOT touch `factory/**` in the repo root.
4. **Verification**:
   - Run Gradle builds and tests: `./gradlew :agentos-factory-bridge-plugin:build` and `./gradlew build` in `agentos/`.

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
    │                           ├── FactoryBridgeExtension.kt (implements SPIs & ToolPlugin)
    │                           ├── FactoryStepResultBindingRegistry.kt
    │                           ├── FactoryStepResultBindingController.kt
    │                           ├── FactoryEnvironmentBindingService.kt
    │                           ├── FactoryCheckpointClient.kt
    │                           ├── FactoryToolGrantService.kt
    │                           ├── FactoryProjectionValidation.kt
    │                           ├── tools/
    │                           │   ├── FactoryEnvironmentTool.kt
    │                           │   ├── FactoryGetWorkflowTool.kt
    │                           │   ├── FactoryPublishProjectionTool.kt
    │                           │   ├── FactoryRecordEvidenceTool.kt
    │                           │   ├── FactoryRequestHumanDecisionTool.kt
    │                           │   ├── FactoryRequestTransitionTool.kt
    │                           │   ├── FactoryStartWorkflowTool.kt
    │                           │   ├── FactorySubmitStepResultTool.kt
    │                           │   └── FactoryTransitionWorkflowTool.kt
    │                           └── dto/ (or data models embedded/adapted as needed)
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

### 2.2 Gradle & Nx Configuration

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
   Modeled after `agentos-bash-plugin` / `agentos-mcp-plugin`:
   - Plugins: `dev.nx.gradle.project-graph`, `kotlin.jvm`, `kotlin.kapt`, `maven-publish`.
   - Dependencies:
     - `compileOnly("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")`
     - `compileOnly(libs.pf4j)` / `kapt(libs.pf4j)`
     - `compileOnly(libs.bundles.jackson)`
     - `compileOnly(libs.okhttp)` / `implementation(libs.okhttp)`
     - `implementation(libs.klogger)`
     - Test dependencies: `agentos-sdk`, `jackson`, `okhttp`, `testing.common`, `pf4j`, `junit.platform.launcher`, `kaptTest(libs.pf4j)`.
   - Manifest:
     - `Plugin-Id`: `agentos-factory-bridge-plugin`
     - `Plugin-Version`: `${version}`
     - `Plugin-Provider`: `whoz-oss`
     - `Plugin-Class`: `io.whozoss.agentos.plugins.factorybridge.FactoryBridgePlugin`

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

4. **`agentos/settings.gradle.kts`**:
   Add `includeBuild("agentos-factory-bridge-plugin")`.

5. **`agentos/build.gradle.kts`**:
   Add `"agentos-factory-bridge-plugin"` to the `pluginBuilds` list so `deployPlugins`, `jarPlugins`, `cleanPlugins` build and deploy the plugin JAR into `agentos/plugins/`.

---

## 3. Implementation Details

### 3.1 Plugin Lifecycle & SPI Extensions
- **`FactoryBridgePlugin`**: Subclasses `org.pf4j.Plugin`. Handles startup/shutdown logging or internal state initialization if needed.
- **`FactoryBridgeExtension`**:
  - Implements:
    - `ToolPlugin` (provides `FACTORY__*` tools for `integrationType = "FACTORY"` / capability matching).
    - `AnswerInterceptor` (validates/intercepts human answers/checkpoints before resumption when Factory workflow policies dictate).
    - `CaseLifecycleObserver` (observes case status changes or event persistence to propagate status/step progress to Factory).
    - `ExternalExecutionContextProvider` (supplies Factory execution context e.g. active workflow/step/binding IDs into `sessionContext`).
    - `ToolGrantPolicy` (evaluates tool grant constraints for Factory workflow steps).
  - Annotate with `@Extension` for PF4J discovery.

### 3.2 Component Adaptation
Copy/adapt components from `agentos-service` (`io.whozoss.agentos.factory.*`) into `agentos-factory-bridge-plugin` (`io.whozoss.agentos.plugins.factorybridge.*`):
1. **Tools**:
   - `FactoryEnvironmentTool` (or `FactoryProvisionEnvironmentTool`)
   - `FactoryGetWorkflowTool`
   - `FactoryPublishProjectionTool`
   - `FactoryRecordEvidenceTool` (`FactoryRecordAgentResultTool`, `FactoryRecordArtifactTool`)
   - `FactoryRequestHumanDecisionTool`
   - `FactoryRequestTransitionTool`
   - `FactoryStartWorkflowTool`
   - `FactorySubmitStepResultTool`
   - `FactoryTransitionWorkflowTool`
2. **Services & Utilities**:
   - `FactoryToolGrantService`
   - `FactoryEnvironmentBindingService`
   - `FactoryStepResultBindingRegistry`
   - `FactoryStepResultBindingController` (if applicable, or endpoint adaptation)
   - `FactoryCheckpointClient`
   - `FactoryProjectionValidation`

### 3.3 Coexistence Strategy
- Core `agentos-service` retains its current files under `io.whozoss.agentos.factory.*` during T2b.
- No files in `agentos-service` or `agentos-sdk` are deleted.
- No files in `factory/**` (repo root) are touched.
- The new plugin resides completely inside `agentos/agentos-factory-bridge-plugin`.

---

## 4. Verification & Testing Strategy

1. **Unit & Integration Tests in Plugin**:
   - Copy and adapt existing tests from `agentos-service/src/test/kotlin/io/whozoss/agentos/factory/*` to `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/`.
   - Test SPI extension implementations (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`, `ToolPlugin`).

2. **Gradle Build Verification**:
   - Test plugin build: `./gradlew :agentos-factory-bridge-plugin:build` inside `agentos/`.
   - Test root build: `./gradlew build` inside `agentos/`.
   - Test deploy task: `./gradlew deployPlugins` inside `agentos/`.

---

## 5. Step-by-Step Execution Plan for Builder

1. **Step 1: Scaffolding**:
   - Create `agentos/agentos-factory-bridge-plugin/` directory structure.
   - Create `settings.gradle.kts`, `build.gradle.kts`, `project.json`.
   - Register module in `agentos/settings.gradle.kts` and `agentos/build.gradle.kts`.

2. **Step 2: Source Code Migration & Extension Implementation**:
   - Create `io.whozoss.agentos.plugins.factorybridge` package.
   - Implement `FactoryBridgePlugin` and `@Extension` implementations of `AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`, `ToolPlugin`.
   - Copy and adapt all Factory tools, binding services, client HTTP handling, and validation logic into the new plugin package.

3. **Step 3: Unit Tests Creation**:
   - Adapt unit/integration tests for tools, SPI hooks, bindings, and client in `agentos-factory-bridge-plugin/src/test/...`.

4. **Step 4: Build & Sanity Check**:
   - Execute `./gradlew build` in `agentos/`.
   - Verify `agentos-factory-bridge-plugin.jar` builds and all tests pass.
