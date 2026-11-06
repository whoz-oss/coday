# AgentOS — Spring Boot 4 / Jackson 3 Migration Study

> Status: **implemented** in one PR (issue #1588) — see [Outcome](boot4-jackson3-migration-outcome.md). The sections below are the
> original proposal, kept for context.
> Goal: align `agentos/gradle/libs.versions.toml` and the build on the Whoz reference catalog
> (Kotlin 2.4.20, Spring Boot 4.1.2, Spring Cloud 2025.1.3, Jackson 3, JVM 25).

## Current state

The migration has already started in the working tree: `agentos/gradle/libs.versions.toml` is modified
(Kotlin 2.3.20 → 2.4.20, Boot 3.5.9 → 4.1.2, `jackson` version removed). Nothing else has followed yet.

**The build is currently broken**: the `jackson-*` library entries still use `version.ref = "jackson"`,
which no longer exists.

## Scope

| Area | Current | Target (reference catalog) | Impact |
|---|---|---|---|
| Jackson | 2.x (`com.fasterxml`) | 3.x (`tools.jackson`) | ~175 files. The SDK exposes `JsonNode` in `ToolPlugin`, `StandardTool`, `ToolExecutionResult` and `CaseEvent`: **the plugin contract breaks** |
| Spring Cloud | 2025.0.1 + hand-versioned starters | 2025.1.3, versions managed by the BOM | Low |
| springdoc | 2.8.9 | 3.0.3 (+ realign the SDK's `swagger-annotations`) | Low |
| Jolokia | `jolokia-support-springboot3` 2.6.1 | `jolokia-support-springboot` 2.6.0 | Low |
| logstash encoder | 8.1 (Jackson 2) | 9.0 (requires Jackson 3) | Depends on the Jackson decision |
| MCP SDK | `mcp-json-jackson2` | `mcp-json-jackson3` (`mcp` bundle) | Depends on the Jackson decision |
| Testcontainers | 1.20.4 | 1.21.4 | Low |
| Spring AI | 1.1.2 | **not in the reference catalog**. 1.1.x does not support Boot 4: 2.0.x required | ~40 files (`ChatModelFactory`, `CompressingChatClient`, `AgentSimple`…) |

## Simplifications enabled by Boot 4

Three workarounds in `agentos-service/build.gradle.kts` only exist because the Boot 3.5 BOM lagged behind:

- Netty forced to 4.2.x (`nettyCoreModules`, netty `runtimeOnly` deps, `resolutionStrategy`).
- Forced kotlinx-coroutines version.
- Exclusion of `neo4j-java-driver` on `neo4j-embedded`.

The Boot 4 BOM most likely aligns all three, but this is **not verified yet**: check
`./gradlew :agentos-service:dependencies` before removing them.

### BOMs as Gradle platforms

Replace the `io.spring.dependency-management` plugin with Gradle-native platforms, as the reference catalog does:

```kotlin
implementation(platform(libs.boot.platform))
implementation(platform(libs.cloud.platform))
implementation(platform(libs.spring.ai.bom))
```

One less resolution mechanism, and the documented composite-build workaround disappears with it.

### Other Boot 4 changes

- `spring-boot-starter-web` → `spring-boot-starter-webmvc`.
- `spring-boot-starter-test` is split into per-technology test starters; some test packages move
  (`@AutoConfigureMockMvc`, etc.).
- `Jackson2ObjectMapperBuilder` is gone: use `JsonMapper.builder()` and Jackson 3 customizers.
  `ObjectMapperConfiguration` gets simpler since Jackson 3 mappers are immutable.

### Kept as is

- **kapt** stays: the PF4J annotation processor is a Java processor, which KSP does not run.

## Jackson 3 code changes

- Packages: `com.fasterxml.jackson.core|databind` → `tools.jackson.core|databind`.
  Annotations stay in `com.fasterxml.jackson.annotation` (unchanged).
- Exceptions are unchecked (`JacksonException` extends `RuntimeException`).
- `JsonNode.fields()` → `properties()`, and other renamed accessors.
- Mappers are immutable: configure through `JsonMapper.builder()` / `rebuild()`.
- Kotlin module: `tools.jackson.module:jackson-module-kotlin`.

## Open decisions

### 1. Full Jackson 3 or temporary Jackson 2 bridge?

- **Option A — full Jackson 3** (what the reference catalog follows). Consistent with
  `jackson3-module-kotlin`, `mcp-json-jackson3`, logstash 9. Cost: SDK bumps to **4.0.0** and every
  external plugin must be recompiled, plus the code changes listed above.
- **Option B — stay on Jackson 2 for now** through Boot 4's deprecated `spring-boot-jackson2` module.
  Smaller diff, but known debt, and it contradicts the reference catalog for logstash and MCP.

**Recommendation: Option A**, done once and properly — the SDK contract breaks anyway as soon as
`JsonNode` changes.

### 2. Spring AI 2.0.x version

Not in the reference catalog: which 2.0.x version is validated at Whoz?

## Proposed plan

One PR per phase:

1. **Catalog and build files** — BOMs as `platform()`, aligned versions, workarounds removed.
2. **Jackson 3** — SDK first, then service, then plugins.
3. **Spring AI 2.0**.
4. **Boot 4 web and test API changes**.

Phases 2 to 4 require many compile iterations: run `./gradlew build` between each step, or delegate
them to Sway in a dedicated worktree.
