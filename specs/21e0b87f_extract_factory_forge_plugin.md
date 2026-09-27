# Architectural Plan: Extract forge/BMAD into PF4J Plugin (`factory-forge-plugin`)

## Overview & Objectives
Extract all Forge/BMAD code, models, controllers, and services from `factory-service` into a standalone PF4J plugin module named `factory-forge-plugin/`.
Make `factory-service` generic (no forge/dev dependencies or imports).

### Invariants & Requirements
1. **Zero Imports Invariant**: `grep -rn "import io.whozoss.factory.forge" factory-service/src` MUST return 0 matches.
2. **Core Without Plugin**: `factory-service` starts cleanly with an empty `plugins/` directory. Core routes respond; `/api/forge/*` and `/api/jira/*` return `404`.
3. **Core With Plugin**: When `factory-forge-plugin` JAR is deployed to `plugins/`, `/api/forge/*` and `/api/jira/*` endpoints respond identically via PF4J's `FactoryRouteContributor`.
4. **OpenAPI Core Spec**: `factory-service/openapi/factory-openapi.yaml` must contain ONLY core endpoints (no `/api/forge/*` or `/api/jira/*`). `check-openapi-spec.sh` must pass on core. A plugin OpenAPI spec is placed in `factory-forge-plugin/openapi/factory-forge-plugin-openapi.yaml`.
5. **No Node Touches**: Do NOT touch Node control plane (`factory/dashboard`) or Node instrument (`factory/run.mjs`, workflows, lib).

---

## Detailed Step-by-Step Execution Plan

### Step A: Refactor & Promotes Generic Components in `factory-service` (pre-extraction)

1. **Move Workstream Component**:
   - Source files in `factory-service/src/main/kotlin/io/whozoss/factory/forge/`:
     - `web/WorkstreamController.kt`
     - `service/WorkstreamService.kt`
     - `infrastructure/JdbcWorkstreamRepository.kt`
   - Test files in `factory-service/src/test/kotlin/io/whozoss/factory/forge/`:
     - `WorkstreamJdbcRepositoryTest.kt`
   - Move to new package `io.whozoss.factory.workstream`:
     - Directory: `factory-service/src/main/kotlin/io/whozoss/factory/workstream/`
     - Update package declarations to `package io.whozoss.factory.workstream`.
     - Update references/imports across `factory-service`.

2. **Move AgentOS Proxy Component**:
   - Source files:
     - `web/AgentOsProxyController.kt`
     - `port/AgentOsProxyClient.kt`
     - `infrastructure/HttpAgentOsProxyClient.kt`
   - Test files:
     - `AgentOsProxyMockTest.kt`
   - Move to new package `io.whozoss.factory.proxy` or `io.whozoss.factory.web`:
     - Move `AgentOsProxyController.kt` to `io.whozoss.factory.proxy.web` (or `io.whozoss.factory.proxy`).
     - Move `AgentOsProxyClient.kt` and `HttpAgentOsProxyClient.kt` to `io.whozoss.factory.proxy`.
     - Update package declarations. Endpoints `/api/agents` and `/api/cases/{id}/events` remain unchanged in path and behavior.

3. **Extract Generic HTTP Helpers from `ForgeHttp.kt`**:
   - Extract generic caller and error helpers from `io.whozoss.factory.forge.web.ForgeHttp.kt` into `io.whozoss.factory.web.FactoryHttp` (or `HttpUtils.kt` in `io.whozoss.factory.web`):
     - Function `resolveCaller(trustContext: TrustContext?, tenantScopeProvider: TenantScopeProvider): FactoryCaller` (or `resolveForgeCaller` alias if needed by plugin, but core uses `resolveCaller` / `resolveFactoryCaller`).
     - Function `factoryError(statusCode: Int, code: String, message: String, details: Any? = null): Nothing` / `FactoryHttpException`.
     - Function `requireNamespaceQuery(namespaceId: String?): String`.
   - Update `factory-service/src/main/kotlin/io/whozoss/factory/runs/web/LegacyRunController.kt` and `LegacyRunSseController.kt`:
     - Replace import `io.whozoss.factory.forge.web.*` with generic `io.whozoss.factory.web.*` functions.
   - Verify strictly: `grep -rn "import io.whozoss.factory.forge" factory-service/src/main/kotlin/io/whozoss/factory/runs` MUST return 0 matches.

4. **Decouple `ForgeConfiguration`**:
   - Move `legacyRunService` bean definition from `ForgeConfiguration.kt` into `io.whozoss.factory.runs.config.LegacyRunConfiguration.kt` (or `RunsConfiguration.kt`).
   - Create `LegacyRunProperties` or bind `factory.runs` properties (with fallback/aliases if needed) so `LegacyRunService` is instantiated independently of `ForgeProperties` / `ForgeConfiguration`.

---

### Step B: Create Plugin Module `factory-forge-plugin/`

1. **Directory Structure**:
   Create directory `factory-forge-plugin/` at repository root with structure:
   ```
   factory-forge-plugin/
   ├── build.gradle.kts
   ├── settings.gradle.kts
   ├── project.json
   ├── openapi/
   │   └── factory-forge-plugin-openapi.yaml
   └── src/
       ├── main/
       │   ├── kotlin/
       │   │   └── io/whozoss/factory/forge/
       │   └── resources/
       └── test/
           └── kotlin/
               └── io/whozoss/factory/forge/
   ```

2. **Configuration Files**:
   - `factory-forge-plugin/settings.gradle.kts`:
     ```kotlin
     rootProject.name = "factory-forge-plugin"
     enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
     includeBuild("../factory-sdk") {
         dependencySubstitution {
             substitute(module("io.whozoss.factory:factory-sdk")).using(project(":"))
         }
     }
     dependencyResolutionManagement {
         repositories {
             mavenCentral()
             mavenLocal()
         }
     }
     ```
   - `factory-forge-plugin/build.gradle.kts`:
     - Plugins: `kotlin("jvm")`, `kotlin("kapt")`, `maven-publish`, `id("dev.nx.gradle.project-graph") version "0.1.10"`.
     - Dependencies:
       - `compileOnly(project(":factory-sdk"))`
       - `compileOnly(libs.pf4j)`, `kapt(libs.pf4j)` (Kapt with `pf4j.storageClassName = "org.pf4j.processor.LegacyExtensionStorage"`)
       - Runtime dependencies provided by core classloader (`compileOnly`): Spring Web/Context, Jackson, Spring Data JDBC.
     - Jar task manifest:
       ```kotlin
       manifest {
           attributes(
               "Plugin-Id" to "factory-forge-plugin",
               "Plugin-Version" to "1.0.0",
               "Plugin-Provider" to "whoz-oss",
               "Plugin-Class" to "io.whozoss.factory.forge.plugin.ForgePlugin"
           )
       }
       ```
     - Klogger or required bundled runtime classpath inclusions (if any).
   - `factory-forge-plugin/project.json`:
     ```json
     {
       "name": "factory-forge-plugin",
       "$schema": "../node_modules/nx/schemas/project-schema.json",
       "projectType": "library",
       "sourceRoot": "factory-forge-plugin/src",
       "tags": ["type:lib", "platform:jvm", "scope:plugin"],
       "targets": {
         "build": {
           "executor": "nx:run-commands",
           "options": {
             "command": "./gradlew jar",
             "cwd": "factory-forge-plugin"
           }
         },
         "test": {
           "executor": "nx:run-commands",
           "options": {
             "command": "./gradlew test",
             "cwd": "factory-forge-plugin"
           }
         }
       }
     }
     ```

3. **Gradle Build / Packaging Task in `factory-service`**:
   - Add a task or script/target (e.g. `deployPlugins` or `./gradlew buildForgePlugin`) to assemble the plugin JAR and copy it into `factory-service/plugins/` or root `plugins/` directory.

---

### Step C: Move Forge Domain & Logic into `factory-forge-plugin`

1. **Move Forge Packages**:
   - Move from `factory-service` to `factory-forge-plugin`:
     - `forge/domain` (`ForgeSpec`, `ForgeStorySpec`, `ForgeLedger`, `ForgeRoots`, `ForgeHumanDecision`, `ForgeFrontOracleResolution`, `ForgeWorkflowAdapter`, `ForgePlan`, `ForgeStoryOperations`, `ForgeSupport`, `JiraDomain`, `WorkflowSync`)
     - `forge/service` (`ForgeGateService`, `ForgeRunService`, `StoryOperationService`)
     - `forge/infrastructure` (`FileForgeLedgerStore`, `ForgeRootsResolver`, `ForgeSpecReader`, `HttpJiraClient`)
     - `forge/port` (`ForgeLedgerStore`, `JiraClient`)
     - `forge/config` (`ForgeProperties`, `ForgeConfiguration`)
   - Package names: keep as `io.whozoss.factory.forge.*`.

2. **Implement PF4J Extension Classes in `factory-forge-plugin`**:
   - Create `ForgePlugin : org.pf4j.Plugin(wrapper)`.
   - **Route Contributor (`FactoryRouteContributor`)**:
     - Implement `@Extension class ForgeRouteContributor : FactoryRouteContributor`.
     - Bridge endpoints `/api/forge/*` and `/api/jira/*` into `FactoryRoute` list with `FactoryRouteHandler`.
     - Remove Spring `@RestController` / `@RequestMapping` annotations from plugin controllers if converting to functional handlers, or invoke `ForgeRunController` / `JiraProxyController` logic directly inside `FactoryRouteHandler`.
   - **Workflow Projection Publisher (`FactoryWorkflowProjectionPublisher`)**:
     - Implement `@Extension class ForgeWorkflowProjectionPublisher : FactoryWorkflowProjectionPublisher`.
     - Delegate to `ForgeWorkflowAdapter.adapt(projection)`.
   - **Run Launch Contributor (`FactoryRunLaunchContributor`)**:
     - Implement if forge run launch uses launch SPI hooks.

3. **Plugin Configuration & Properties**:
   - Configuration read from sysprops (`factory.forge.*`) or environment variables (`AGENTOS_URL`, `JIRA_*`, etc.).
   - Remove `factory.forge.*` block from `factory-service/src/main/resources/application.yml`.

---

### Step D: Restrict Core `@ComponentScan`

1. In `factory-service/src/main/kotlin/io/whozoss/factory/FactoryServiceApplication.kt`:
   - Annotate `@SpringBootApplication(scanBasePackages = ["io.whozoss.factory"])`.
   - Since `forge` is completely removed from `factory-service/src/main/kotlin/io/whozoss/factory/`, Spring component scan will natively find no `forge` classes.

---

### Step E: OpenAPI Spec Separation

1. **Core OpenAPI Spec (`factory-service/openapi/factory-openapi.yaml`)**:
   - Remove all paths for `/api/forge/*` and `/api/jira/*`.
   - Keep `/api/agents`, `/api/cases/{caseId}/events`, `/api/factory/workstreams`, etc.
   - Run `check-openapi-spec.sh` to confirm regeneration and matching diff.

2. **Plugin OpenAPI Spec (`factory-forge-plugin/openapi/factory-forge-plugin-openapi.yaml`)**:
   - Place `/api/forge/*` and `/api/jira/*` endpoint definitions in `factory-forge-plugin/openapi/factory-forge-plugin-openapi.yaml`.

---

### Step F: Verification, Integration Tests & Invariants

1. **Grep Invariant**:
   - Run: `grep -rn "import io.whozoss.factory.forge" factory-service/src`
   - MUST return 0 matches.

2. **Core Integration Test (Without Plugin)**:
   - In `factory-service/src/test/kotlin/io/whozoss/factory/plugin/FactoryPluginSystemIntegrationTest.kt`:
     - Verify application boots with empty `plugins/` directory.
     - Verify `/api/forge/runs` returns 404 (Not Found).
     - Verify core endpoints `/api/factory/workstreams` and `/api/agents` return 200 OK.

3. **Plugin Integration Test (With Plugin)**:
   - Create `FactoryForgePluginIntegrationTest.kt` extending `DomainIntegrationTest`.
   - Build plugin JAR into `plugins/` directory before test or load plugin dynamically in test context.
   - Verify `/api/forge/runs` and `/api/jira/{ticketId}` endpoints respond correctly.

4. **Full Test Suite Execution**:
   - Run `./gradlew clean test` on `factory-sdk`, `factory-service`, and `factory-forge-plugin`.

---

## File Changes Summary

| Action | Path | Description |
|---|---|---|
| Move / Refactor | `factory-service/src/main/kotlin/io/whozoss/factory/workstream/*` | Workstream controller, service, repository |
| Move / Refactor | `factory-service/src/main/kotlin/io/whozoss/factory/proxy/*` | AgentOS proxy controller and HTTP client |
| Create | `factory-service/src/main/kotlin/io/whozoss/factory/web/FactoryHttp.kt` | Generic HTTP callers, error helpers |
| Refactor | `factory-service/src/main/kotlin/io/whozoss/factory/runs/web/LegacyRun*.kt` | Remove `io.whozoss.factory.forge` imports |
| Create | `factory-forge-plugin/build.gradle.kts` | Gradle plugin build script |
| Create | `factory-forge-plugin/settings.gradle.kts` | Gradle plugin settings script |
| Create | `factory-forge-plugin/project.json` | Nx project definition |
| Move | `factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/*` | All Forge domain, service, infra, port, config |
| Create | `factory-forge-plugin/src/main/kotlin/.../ForgePlugin.kt` | PF4J Plugin entry point & Extensions |
| Edit | `factory-service/openapi/factory-openapi.yaml` | Remove Forge/Jira paths (Core only) |
| Create | `factory-forge-plugin/openapi/factory-forge-plugin-openapi.yaml` | Plugin OpenAPI spec |
| Test | `factory-service/src/test/kotlin/io/whozoss/factory/plugin/*` | Core without plugin vs with plugin integration tests |
