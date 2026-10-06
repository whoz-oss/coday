# Implementation Plan: TASK W7.2 — factory-sdk module and PF4J Plugin Infrastructure in factory-service

## Overview
This task creates the `factory-sdk` Gradle module for Coday's `factory-service` runtime and sets up PF4J extension host infrastructure within `factory-service`.
This mirroring of `agentos-sdk` / `agentos-service` enables decoupled plugins in `factory-service` while maintaining 100% backward compatibility with existing routes (`/api/forge/...`) and existing Node/Forge logic.

---

## Key Constraints & Guardrails
1. **Control Plane Isolation**: DO NOT touch Node control plane (`factory/dashboard`) or Node instrument (`factory/run.mjs`, workflows, lib).
2. **Forge/BMAD Stability**: `forge/BMAD` MUST stay in place inside `factory-service` and remain 100% functional (no domain extraction in W7.2).
3. **`factory-sdk` Dependencies**: Only PF4J + jackson-annotations + kotlin-stdlib ("Only PF4J, No Spring Boot").
4. **Toolchain Alignment**: Java 25 target, Kotlin 2.3.20 (matching `factory-service/gradle/libs.versions.toml`).
5. **Testing**: Add an integration test extending `DomainIntegrationTest` verifying clean startup and functional `/api/forge/...` endpoints with an empty `plugins/` folder. Both `./gradlew test` in `factory-service` and `pnpm nx test factory-service` must pass 100% green.

---

## Proposed Changes & Step-by-Step Implementation

### Step 1: Create `factory-sdk` Module in `/work/app/factory-sdk`

#### 1.1 Directory & File Layout
```
factory-sdk/
├── build.gradle.kts
├── settings.gradle.kts
├── project.json
└── src/
    └── main/
        └── kotlin/
            └── io/
                └── whozoss/
                    └── factory/
                        └── sdk/
                            └── spi/
                                ├── FactoryRoute.kt
                                ├── FactoryRouteContributor.kt
                                ├── FactoryWorkflowProjectionPublisher.kt
                                ├── FactoryRunLaunchContributor.kt
                                └── FactorySseSink.kt
```

#### 1.2 `factory-sdk/build.gradle.kts`
- Group: `io.whozoss.factory` (or `whoz-oss.factory`, aligned with `factory-service`)
- Version: `0.0.1-SNAPSHOT`
- Plugins: `kotlin("jvm")`, `maven-publish`
- Dependencies:
  - `compileOnly` / `api` for `org.pf4j:pf4j:3.13.0` (exclude slf4j-reload4j/log4j12 if present)
  - `api` for `com.fasterxml.jackson.core:jackson-annotations`
  - `implementation` for `kotlin-stdlib`
  - NO Spring Boot dependencies.
- Configure `publishing` to publish to `mavenLocal()`.

#### 1.3 `factory-sdk/project.json`
Nx project descriptor:
```json
{
  "name": "factory-sdk",
  "$schema": "../node_modules/nx/schemas/project-schema.json",
  "projectType": "library",
  "sourceRoot": "factory-sdk/src",
  "targets": {
    "build": {
      "executor": "nx:run-commands",
      "cache": true,
      "options": {
        "command": "./gradlew build",
        "cwd": "factory-sdk"
      }
    },
    "test": {
      "executor": "nx:run-commands",
      "cache": false,
      "options": {
        "command": "./gradlew test",
        "cwd": "factory-sdk"
      }
    }
  },
  "tags": ["type:lib", "platform:jvm", "scope:lib"]
}
```

#### 1.4 `factory-sdk/settings.gradle.kts`
```kotlin
rootProject.name = "factory-sdk"
```

#### 1.5 Define ExtensionPoints and SPIs in `factory-sdk`
Package: `io.whozoss.factory.sdk.spi`

1. `FactoryRoute`:
   - Data class/descriptor representing a Spring-agnostic route.
   - Fields: `method` (String, e.g. "GET", "POST"), `path` (String, e.g. "/api/factory/custom"), `handler` (functional interface or lambda taking a request map/context and returning a response object or ResponseEntity-like structure, OR functional descriptor).
   - Alternatively: A Spring-agnostic descriptor `FactoryRoute(val method: String, val path: String, val handler: (FactoryRouteRequest) -> FactoryRouteResponse)`.

2. `FactoryRouteContributor`:
   - Extends `org.pf4j.ExtensionPoint`.
   - Interface method: `fun getRoutes(): List<FactoryRoute> = emptyList()`

3. `FactoryWorkflowProjectionPublisher`:
   - Extends `org.pf4j.ExtensionPoint`.
   - Interface methods for projection contribution/hook with safe defaults:
     `fun publishProjection(namespaceId: String, projection: Map<String, Any?>): Boolean = false` (or returns null/Unsupported by default).

4. `FactoryRunLaunchContributor`:
   - Extends `org.pf4j.ExtensionPoint`.
   - Interface method: `fun onRunLaunch(runId: String, payload: Map<String, Any?>) {}` (default no-op).

5. `FactorySseSink`:
   - Interface for SSE string sink contract: `fun send(event: String, data: String)` / `fun close()`.

---

### Step 2: Configure `factory-service` Dependencies & Version Catalog

#### 2.1 Update `factory-service/gradle/libs.versions.toml`
Add PF4J versions and library definitions:
```toml
[versions]
# ... existing versions
pf4j = "3.13.0"
pf4jSpring = "0.10.0"

[libraries]
# ... existing libraries
pf4j = { module = "org.pf4j:pf4j", version.ref = "pf4j" }
pf4j-spring = { module = "org.pf4j:pf4j-spring", version.ref = "pf4jSpring" }
```

#### 2.2 Update `factory-service/build.gradle.kts` and `settings.gradle.kts`
- Option A: Add `includeBuild("../factory-sdk")` in `factory-service/settings.gradle.kts` so Gradle composite build resolves `implementation("io.whozoss.factory:factory-sdk:0.0.1-SNAPSHOT")` or `implementation(project(":factory-sdk"))` transparently.
- Option B: Alternatively, include `factory-sdk` as a multi-project or includeBuild. `includeBuild("../factory-sdk")` is clean, standard in Nx monorepos, and ensures `factory-sdk` is built automatically when `factory-service` builds.
- In `factory-service/build.gradle.kts`:
  ```kotlin
  implementation("io.whozoss.factory:factory-sdk:0.0.1-SNAPSHOT")
  implementation(libs.pf4j.spring) {
      exclude(group = "org.slf4j", module = "slf4j-reload4j")
  }
  ```

---

### Step 3: Set Up PF4J Infrastructure in `factory-service`

#### 3.1 Properties Configuration
Create `io.whozoss.factory.config.FactoryPluginConfigProperties`:
```kotlin
package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("factory.plugins")
data class FactoryPluginConfigProperties(
    val dir: String = "plugins/",
)
```

#### 3.2 `PluginConfiguration` Bean
Create `io.whozoss.factory.config.PluginConfiguration`:
- Patterned exactly after `AgentOS` `PluginConfiguration.kt`.
- Uses `NullSafeSpringPluginManager` with `ApdJarPluginLoader` and `ApdDefaultPluginLoader` (Application → Plugin → Dependencies loading strategy) and `NullSafeSpringExtensionFactory`.
- Creates `plugins/` directory if absent on bean initialization.
- Exposes `PluginManager` bean.

#### 3.3 Dynamic Route Mounting Mechanism
Create `io.whozoss.factory.config.FactoryPluginRouteConfig`:
- Spring `@Configuration` bean that injects `PluginManager`.
- Retrieves all `FactoryRouteContributor` extensions via `pluginManager.getExtensions(FactoryRouteContributor::class.java)`.
- Defines a Spring `@Bean fun pluginRouterFunction(contributors: List<FactoryRouteContributor>): RouterFunction<ServerResponse>` (or retrieves extensions directly inside the bean definition method).
- Converts each `FactoryRoute` (e.g. method + path + handler) into Spring WebMvc `RouterFunctions.route()`.
  - For HTTP handling: maps `FactoryRouteRequest` from Spring `ServerRequest` (body, headers, query params) and maps `FactoryRouteResponse` to Spring `ServerResponse`.

---

### Step 4: Integration Testing & Verification

#### 4.1 Integration Test `FactoryPluginSystemIntegrationTest`
Create `factory-service/src/test/kotlin/io/whozoss/factory/plugin/FactoryPluginSystemIntegrationTest.kt`:
- Extends `DomainIntegrationTest`.
- Tests that:
  1. Application context starts cleanly when `plugins/` is empty.
  2. `PluginManager` bean is present and initialized.
  3. Pre-existing forge endpoints (e.g. `GET /api/forge/runs`) remain 100% functional and return HTTP 200 OK (or expected empty list JSON response).
  4. Dynamic router function bean loads without error even when 0 plugin routes are registered.

#### 4.2 Test Suite Run
- Run `cd factory-sdk && ./gradlew test` (or `pnpm nx test factory-sdk` once added).
- Run `cd factory-service && ./gradlew clean test` (or `pnpm nx test factory-service`).
- Verify no existing tests broke and everything builds cleanly.

---

## Verification Plan

### Automated Tests
1. `cd factory-sdk && ./gradlew build` -> Ensure `factory-sdk` compiles and publishes or packages cleanly.
2. `cd factory-service && ./gradlew test` -> All unit and integration tests pass, including the new `FactoryPluginSystemIntegrationTest`.
3. `pnpm nx test factory-service` -> Nx test target succeeds.

### File Verification
- Check that `factory-sdk` contains only `PF4J`, `jackson-annotations`, and `kotlin-stdlib` in its compile classpath.
- Check that `forge/BMAD` and all Node files are untouched.
