# Scout findings — Generic Factory core + forge plugin (AgentOS-style) design inputs

> Read-only reconnaissance for the design doc `docs/factory-generic-core-forge-plugin-design.md`.
> All paths are relative to repo root `/work/app`. Line numbers are indicative.
> Note: 5 parallel subagents were spawned; their textual reports were not surfaced back to this
> agent, so **every fact below was verified first-hand** with read/grep/find. Coverage is complete
> for both axes of the mission.

---

## 0. TL;DR for the design author

- **AgentOS already gives us a production-grade, proven plugin pattern to imitate**: PF4J + an SDK
  module holding `ExtensionPoint` interfaces, plugins as separate Gradle `includeBuild`s, discovered
  by a `SpringPluginManager`, deployed by copying JARs into `agentos/plugins/`, with a hard rule that
  **the core never depends on a plugin**. The `agentos-factory-bridge-plugin` is itself the result of
  a previous "extract the domain out of the core into a plugin" refactor — the exact precedent requested.
- **`factory-service` (Kotlin) has NO plugin infrastructure today**: `@SpringBootApplication` on
  `io.whozoss.factory` implicitly component-scans `forge/**` and `runs/**`; there is no SPI, no
  `@ComponentScan`, no ServiceLoader, no pf4j. The forge aggregate sits in the *same source set* as the
  generic primitives.
- **The good news — the code-level coupling is nearly zero**: no generic package imports from
  `forge.*` or `runs.*` (grep-verified, zero hits). Forge only consumes a tiny generic surface
  (`TrustContext`, `TenantScopeProvider`, `TenantScope`, `ScopedRepository`, `RawSseEvent`,
  `FactoryException`/`ConflictException`/`BadRequestException`, `WorkflowProjectionValidator`).
  The couplings to break are **wiring/config/contract-level**: default component scan, `application.yml`
  `factory.forge.*`, the single generated OpenAPI spec, and a couple of files added to core `web/` for
  the runs SSE.
- **`workstreams` is a generic tenant concept** (part of every composite PK in V2→V6 migrations, plus
  `workstream_memberships`, `workstream_repositories`, `workstream_workflow_grants`), yet its only API
  (`WorkstreamController`, `JdbcWorkstreamRepository`) lives under `forge/`. This needs an explicit
  core-vs-plugin decision.
- **`forge-epic.mjs` is effectively orphaned**: it is registered in the run dispatcher but **no
  production caller passes `workflow forge-epic`**. Both the Node dashboard and the Kotlin
  `ForgeRunService` create the EpicRun ledger *in-process* via `createEpicRun`. It remains reachable
  only by manual CLI. Key input to §6 (W6b).

---

## 1. AXE 1 — The AgentOS plugin pattern

### 1.1 Framework and modules

- Framework: **PF4J 3.13.0** + **pf4j-spring 0.10.0** — `agentos/gradle/libs.versions.toml:15-16, 99-100`.
- `agentos/settings.gradle.kts` includes every plugin as a composite build:
  `includeBuild("agentos-sdk")`, `includeBuild("agentos-service")`, `includeBuild("agentos-datetime-plugin")`,
  `includeBuild("agentos-file-plugin")`, …, `includeBuild("agentos-factory-bridge-plugin")`.
- `agentos/agentos-sdk/` is the **Plugin SDK**, published to GitHub Packages + `mavenLocal`. Its README
  states the dependency contract: **only PF4J, no Spring Boot** (`agentos/agentos-sdk/README.md:11`).
  It contains: PF4J-based plugin interfaces, core domain models, extension points, and the REST `api.*`
  contract DTOs shared with consumers.
- `agentos/agentos-service/` is the host: Spring Boot app that owns the `PluginManager` and resolves
  extensions at runtime (`agentos/agentos-service/build.gradle.kts:83` `implementation(libs.pf4j.spring)`).

### 1.2 The plugin contract (what a plugin must implement)

Two artifacts per plugin:

1. **A `Plugin` entry class** extending `org.pf4j.Plugin` (lifecycle `start()`/`stop()`).
   - e.g. `agentos-datetime-plugin/src/main/kotlin/io/whozoss/agentos/plugins/datetime/DateTimePlugin.kt:12`;
   - `agentos-factory-bridge-plugin/.../FactoryBridgePlugin.kt` (delegates to a plugin-classloader
     singleton holder `FactoryBridgePluginHolder`).
2. **`@Extension` classes implementing one or more SDK `ExtensionPoint` interfaces.**

Extension points (all in `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/`, all extend
`org.pf4j.ExtensionPoint`):

| SPI | File | Purpose / safe default |
|---|---|---|
| `ToolPlugin` | `sdk/tool/ToolPlugin.kt` | Typed factory: declares `integrationType`, `configSchema` (JSON Schema as `JsonNode`), returns `List<StandardTool<*>>` from a config. Optional `describeNamespace(...)`. |
| `ToolGrantPolicy` | `sdk/spi/ToolGrantPolicy.kt` | Per-tool grant decision; default `Neutral`; fail-open on exceptions. |
| `CaseLifecycleObserver` | `sdk/spi/CaseLifecycleObserver.kt` | Observes case status transitions / events stored; no-op defaults; exceptions swallowed. |
| `AnswerInterceptor` | `sdk/spi/AnswerInterceptor.kt` | Validates a user answer before persistence; default `Accept`. |
| `ExternalExecutionContextProvider` | `sdk/spi/ExternalExecutionContextProvider.kt` | Enriches session context; default empty map. |
| `UserContextProvider` | `sdk/scheduledPrompt/UserContextProvider.kt` | Scheduled-prompt context. |
| `AiProvider` | `sdk/aiProvider/AiProvider.kt` | AI provider pluggability. |

Plugin descriptor — **two supported mechanisms**:
- `src/main/resources/plugin.properties` with keys `plugin.id`, `plugin.version`, `plugin.provider`,
  `plugin.class`, `plugin.description`, `plugin.license`, `plugin.requires`
  (e.g. `agentos-datetime-plugin/src/main/resources/plugin.properties`; `agentos-file-plugin/.../plugin.properties:1`).
- **or** JAR manifest attributes `Plugin-Id`, `Plugin-Version`, `Plugin-Provider`, `Plugin-Class`
  (used by the factory-bridge plugin, see `agentos-factory-bridge-plugin/build.gradle.kts:107-115`).

Annotation processing: kapt with `arg("pf4j.storageClassName", "org.pf4j.processor.LegacyExtensionStorage")`
generates `META-INF/extensions.idx` (see every plugin's `build.gradle.kts`, e.g. datetime plugin ~L88,
factory-bridge L95-99). PF4J consumes that index to enumerate `@Extension` classes.

### 1.3 Discovery / loading / activation

- `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/config/PluginConfiguration.kt`:
  - `@Configuration class PluginConfiguration` exposes a `PluginManager` bean built as
    `NullSafeSpringPluginManager(Path(pluginsConfigProperties.dir))` with
    `applicationContext` injected (`PluginConfiguration.kt:31-46`).
  - `NullSafeSpringPluginManager : SpringPluginManager` overrides:
    - `createExtensionFactory()` → a null-safe `SpringExtensionFactory` (handles extensions on the app
      classpath that have no plugin wrapper — avoids NPE);
    - `createPluginLoader()` → `CompoundPluginLoader` of `ApdJarPluginLoader` + `ApdDefaultPluginLoader`,
      both forcing **`ClassLoadingStrategy.APD`** (Application → Plugin → Dependencies) instead of PF4J's
      default PDA. Rationale documented at length in the file header: shared classes (Jackson, OkHttp,
      coroutines) must resolve to the **service** classloader so types crossing the boundary share one
      class instance (no `LinkageError`).
- Plugin directory is **configuration-driven**:
  - `AgentOsPluginsConfigProperties` (`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/config/PluginConfigProperties.kt:9`) → `dir: String`.
  - `application.yml:210-211` → `agentos.plugins.dir: ${PLUGINS_DIR:plugins/}`;
    `application-docker.yml:38-39` → `/app/plugins/`.
- Lifecycle management: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/plugin/PluginService.kt`
  (`loadPlugin`, `unloadPlugin`, `reloadPlugin`, `startPlugin`, `stopPlugin`, `getLoadedPlugins`) using
  `PluginManager`. Debug helper: `plugin/PluginDebugService.kt` (inspects `META-INF/extensions.idx`).
- **Extension resolution = the core looks up implementations it does not name**:
  - `pluginManager.getExtensions(X::class.java)` at:
    - `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/tool/ToolRegistryService.kt:67` (`ToolPlugin`);
    - `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/plugin/UserContextProviderResolver.kt:30` (`UserContextProvider`).
  - SPI beans are injected as **`List<X>`** (Spring collects all beans) into the runtime:
    - `caseFlow/CaseServiceImpl.kt:83,88,93,98` (`AnswerInterceptor`, `CaseLifecycleObserver`,
      `ExternalExecutionContextProvider`, `ToolGrantPolicy`);
    - `caseFlow/CaseRuntime.kt:90-91` and call sites at `CaseRuntime.kt:201-210, 275`;
    - `agent/AgentExecutionContext.kt:41` + `agent/AgentServiceImpl.kt:466-476` (grant policies, fail-open).
  - Every SPI has a **safe default** (empty list / no-op / Neutral / Accept / empty map) and callers
    catch exceptions — so an absent plugin cannot break the core. This is the key isolation guarantee
    to reproduce in Factory.

### 1.4 Packaging and dependency direction

- Each plugin is its own Gradle build (`settings.gradle.kts` + `build.gradle.kts` + `project.json` with
  tags `type:lib`, `platform:jvm`, `scope:plugin`).
- Plugin `build.gradle.kts` (e.g. `agentos-datetime-plugin/build.gradle.kts`,
  `agentos-factory-bridge-plugin/build.gradle.kts`):
  - applies `kotlin.jvm`, `kotlin.kapt`, `maven-publish`;
  - deps: `compileOnly("whoz-oss.agentos:agentos-sdk:<version>")`, `compileOnly(libs.pf4j)` (kapt too),
    `compileOnly` Jackson/OkHttp (provided by the host classloader);
  - `tasks.jar` bundles **only the plugin's own runtime deps** (e.g. klogger) into the JAR, excluding
    `META-INF/*.SF…` and `kotlin/**`, `kotlinx/**`.
- **Deployment**: `agentos/build.gradle.kts` root declares `val pluginBuilds = listOf(...)` (L28-40) and
  tasks `cleanPlugins`, `jarPlugins`, `deployPlugins` that build each included plugin and **copy its
  JAR into `agentos/plugins/`** (L45-105). `coday.yaml:99-106` exposes this as `deploy-plugins` →
  `bash ./scripts/deploy-plugins.sh`.
- **Isolation rule (verified)**: `agentos-service` and `agentos-sdk` build files contain **zero**
  references to any plugin module. The plugin list appears only in the *root* settings/build for
  deployment. So the dependency direction is strictly **plugin → sdk/service**, never core → plugin.

### 1.5 The existing precedent: `agentos-factory-bridge-plugin`

- Extracted from agentos-service into a standalone PF4J plugin by specs
  `specs/90527113_extract_factory_bridge_plugin.md` and `specs/f5e09c98_extract_factory_bridge_plugin.md`
  (Task T2b). Explicit constraints in that spec: AgentOS core must start cleanly without the plugin;
  do NOT touch `factory/**`; coexistence mandatory during the transition.
- Plugin content (`agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/`):
  `FactoryBridgePlugin.kt`, `FactoryToolPlugin.kt`, `FactoryAnswerInterceptor.kt`,
  `FactoryCaseLifecycleObserver.kt`, `FactoryExternalExecutionContextProvider.kt`,
  `FactoryToolGrantPolicy.kt`, plus tools under `tools/Factory*Tool.kt` (start/transition/get workflow,
  publish projection, record evidence, request human decision/transition, submit step result,
  environment), a `FactoryCheckpointClient`, `FactoryStepResultBindingRegistry/Controller`,
  `FactoryEnvironmentBindingService`, `FactoryProjectionValidation`.
- Config is read from **system properties / env**, not Spring: `FactoryBridgeConfig.kt` (`agentos.factory.base-url`
  / `AGENTOS_FACTORY_BASE_URL`, default `http://localhost:3141`; `AGENTOS_FACTORY_RUNTIME_ID`).
- This is the template for "forge = optional domain plugin": a separate Gradle module, using core SPI,
  configurable, absent-safe.

### 1.6 Distinction to keep in the design doc

There are **two different "plugin" notions** in this repo — do not conflate them:
- **Code plugin** = PF4J module (AgentOS style). This is what the mission asks to imitate.
- **Coday domain overlay** = `forge_bmad/coday/` (agents/prompts/skills/scripts as YAML+TS) — a Coday
  *project* configuration, not a code plugin. The forge domain plugin will likely ship a Coday overlay
  *and* a Kotlin module.

---

## 2. AXE 2a — The forge aggregate in `factory-service` (Kotlin)

### 2.1 Package inventory (~4,970 LOC) and classification

`factory-service/src/main/kotlin/io/whozoss/factory/forge/**`:

| File | Nature | Notes |
|---|---|---|
| `forge/domain/ForgeLedger.kt` | dev-specific | `FORGE_LEDGER_SCHEMA_VERSION`, `FORGE_WORKFLOW_VERSION = "forge-epic-v1"` (L17), parse/replay JSONL, `projectForgeRun`. |
| `forge/domain/ForgeSpec.kt` | dev-specific | Epic frontmatter parse/validate, scope/oracle validation, SHA-256. |
| `forge/domain/ForgeStorySpec.kt` | dev-specific | Story spec inheritance, hashing. |
| `forge/domain/ForgeHumanDecision.kt` | dev-specific | G1 decision vocabulary, canonical serialization, evidence-set hash. |
| `forge/domain/ForgePlan.kt` | dev-specific | Story analysis plan extraction/validation. |
| `forge/domain/ForgeStoryOperations.kt` | dev-specific | Story phases, diff validation. |
| `forge/domain/ForgeFrontOracleResolution.kt` | dev-specific | Front oracle Nx host resolution — **shells out** `pnpm nx show project` (L90). |
| `forge/domain/ForgeWorkflowAdapter.kt` | dev-specific | adapt Forge run → workflow projection. |
| `forge/domain/WorkflowSync.kt` | dev-specific | workflow sync logic; imports generic `workflow.domain.WorkflowProjectionValidator` / `ProjectionValidation`. |
| `forge/domain/JiraDomain.kt` | dev-specific | Jira ticket domain. |
| `forge/domain/ForgeRoots.kt` / `ForgeSupport.kt` | dev-specific | roots policy + helpers. |
| `forge/service/ForgeGateService.kt` | dev-specific | G1/G2/G2-US evaluation, idempotency, ledger recording. |
| `forge/service/ForgeRunService.kt` | dev-specific | EpicRun creation/projection (port of `dashboard/forge-routes.mjs`); calls `ledgerStore.createEpicRun` in-process. |
| `forge/service/StoryOperationService.kt` | dev-specific | 743 LOC: analysis/edit/oracles execution + recording. |
| `forge/service/WorkstreamService.kt` | **ambiguous** | Workstream CRUD — generic tenant concept, forge packaging. |
| `forge/web/ForgeRunController.kt` | dev-specific | Routes `/api/forge/runs…` + `/api/factory/forge/runs…` (create, G1/G2, story executions/edits/oracles). |
| `forge/web/JiraProxyController.kt` | dev-specific | `/api/jira/{ticketId}` + `/api/factory/jira/{ticketId}`. |
| `forge/web/AgentOsProxyController.kt` | **ambiguous** | `/api/agents`, `/api/cases/{caseId}/events` — generic provider proxy. |
| `forge/web/WorkstreamController.kt` | **ambiguous** | `/api/factory/workstreams` (list/create). |
| `forge/web/ForgeHttp.kt` | dev-specific | namespace/storage helpers. |
| `forge/infrastructure/ForgeRootsResolver.kt`, `ForgeSpecReader.kt`, `FileForgeLedgerStore.kt` | dev-specific | filesystem adapters. |
| `forge/infrastructure/HttpJiraClient.kt` | dev-specific | Jira REST v3. |
| `forge/infrastructure/HttpAgentOsProxyClient.kt` | dev-specific-ish | AgentOS HTTP proxy; default repo root `forge/factory-runs` (L70). |
| `forge/infrastructure/JdbcWorkstreamRepository.kt` | **ambiguous/generic** | `@Repository` over the generic `workstreams` table. |
| `forge/port/ForgeLedgerStore.kt`, `JiraClient.kt`, `AgentOsProxyClient.kt` | dev-specific | ports. |
| `forge/config/ForgeProperties.kt`, `ForgeConfiguration.kt` | dev-specific | `@ConfigurationProperties("factory.forge")`; wires proxy/jira/legacy-run beans. |

`factory-service/src/main/kotlin/io/whozoss/factory/runs/**`:

| File | Nature |
|---|---|
| `runs/service/LegacyRunService.kt` | **legacy run protocol** — spawns `node <runEntry> <workflow>` (L219-226), parses JSONL runs, review gates, in-process active-run registry, SSE framing. Coupled to the Node instrument + Jira error text + `node factory/dashboard/server.mjs` hint. |
| `runs/web/LegacyRunController.kt` | `/api/runs*` + `/api/factory/runs*` (list/launch/detail/stop/review-gate). |
| `runs/web/LegacyRunSseController.kt` | `/api/runs/{id}/stream` + `/api/factory/runs/{id}/stream` (SseEmitter, hidden from OpenAPI). |

Tests: `factory-service/src/test/kotlin/io/whozoss/factory/forge/{ForgeGatesIntegrationTest,ForgeLedgerIntegrationTest,ForgeStoryOperationsTest,WorkstreamJdbcRepositoryTest}.kt`,
`.../forge/domain/ForgeDomainUnitTest.kt`, `.../runs/LegacyRunAndSseHttpTest.kt`.
All extend `DomainIntegrationTest` (postgres testcontainers), per the A8 spec.

### 2.2 What forge consumes from the generic core (the real SPI surface)

Grep of imports from `io.whozoss.factory.*` (excluding forge/runs) across the forge+runs packages:

```
7  io.whozoss.factory.web.TrustContext
7  io.whozoss.factory.persistence.TenantScopeProvider
3  io.whozoss.factory.persistence.TenantScope
1  io.whozoss.factory.persistence.ScopedRepository
1  io.whozoss.factory.web.RawSseEvent
1  io.whozoss.factory.workflow.domain.WorkflowProjectionValidator
1  io.whozoss.factory.workflow.domain.ProjectionValidation
1  io.whozoss.factory.error.FactoryException
1  io.whozoss.factory.error.ConflictException
1  io.whozoss.factory.error.BadRequestException
```

That is the **entire** generic surface forge depends on at compile time — the natural candidate set
for stable extension points / shared kernel. (Note: forge does **not** import WorkUnit, Lease, Artifact,
Oracle, Delivery, Worker, Environment, AgentAttempt, outbox — those relationships are HTTP/projection
level, not code-level. The design should decide whether forge *should* call them through a new SPI.)

### 2.3 Reverse direction — generic core importing forge

**None.** Verified:
`grep -rn "import io.whozoss.factory.forge|import io.whozoss.factory.runs" factory-service/src/main --include=*.kt`
excluding the forge/runs packages → **zero hits**.
`grep -rn "forge" factory-service/src/main/**.kt` excluding forge/runs packages → only the word
"forget" in `delivery/port/DeliveryEvidenceStore.kt:33` and `delivery/port/DeliveryTargetRegistry.kt:41`.

---

## 3. AXE 2b — Couplings heart↔forge to break today (precise list)

These are the things a plugin extraction must untangle (all wiring/config/contract, not imports):

1. **Implicit component scan** — `factory-service/src/main/kotlin/io/whozoss/factory/FactoryServiceApplication.kt:15-17`
   `@SpringBootApplication` + `@ConfigurationPropertiesScan` on package `io.whozoss.factory`, with the
   doc comment "This is the *socle*: no domain aggregate". Because it scans the whole package tree,
   every `@Service/@Component/@Repository/@RestController/@Configuration` under `forge/**` and `runs/**`
   is wired into the core context. There is **no** `@ComponentScan`/`scanBasePackages` anywhere
   (grep-verified). To isolate, the core needs an explicit scan set and the plugin an explicit entry.
2. **Config tree** — `factory-service/src/main/resources/application.yml` `factory.forge.*` block
   (`agentos-url`, `runs-dir`, `run-entry`, `jira.*`). Bound by
   `forge/config/ForgeProperties.kt:14` (`@ConfigurationProperties(prefix = "factory.forge")`).
   Also `factory.tenant.workstream-id` is core. Plugin config should move to the plugin's own prefix
   (cf. AgentOS `agentos.plugins.dir`, or the factory-bridge plugin's sysprops/env).
3. **Single generated OpenAPI spec** — `factory-service/openapi/factory-openapi.yaml` embeds forge/jira/
   workstreams tags and all their paths (e.g. tag `forge` L12-13, `jira` L18-19, `workstreams` L30-31;
   `/api/forge/runs/...` L136+, `/api/factory/workstreams` L325+ and L1341+). Enforced by
   `factory-service/check-openapi-spec.sh` and Nx targets `generate-openapi-spec` /
   `check-openapi-spec` in `factory-service/project.json`. The CI job
   `.github/workflows/validate.yml:82` runs `check-openapi-spec`. A plugin split must decide how plugin
   routes contribute (separate spec, springdoc groups, or exclusion).
4. **`web/RawSseEvent.kt`** was added to the core `web/` package by the A8 commit
   (`git show 72e392eb -- …/web/RawSseEvent.kt`), but it is only used by the runs SSE controllers.
   It is a forge-driven addition sitting in the generic web package.
5. **Migrations / schema** — `factory-service/src/main/resources/db/migration/`:
   `workstreams` (V2:50), `workstream_memberships` (V2:161), `workstream_repositories` (V2:219),
   `workstream_workflow_grants` (V3:94), and `workstream_id` is a component of every composite PK in
   V3/V6 (artifacts, oracle_executions, agent_step_attempts, …). These are **generic tenant schema**
   (V1→V9 are frozen; the A8 spec forbids altering them). No forge-specific table exists; forge durable
   state is JSONL on the filesystem (not Postgres). ⇒ **the `workstreams` table is core**, but the
   `WorkstreamController`/`JdbcWorkstreamRepository` live under `forge/` and should move to core.
6. **SSE** — generic projection SSE is `workflow/sse/WorkflowSseController.kt` +
   `WorkflowSseHub.kt` (route `GET /api/factory/workflows/stream`, hidden from OpenAPI). Forge/legacy
   SSE is `runs/web/LegacyRunSseController.kt`. The only shared bit is `web/RawSseEvent.kt`.
7. **Error/exception types** — forge reuses generic `error.FactoryException`, `ConflictException`,
   `BadRequestException` (fine — they are shared kernel; keep them in core). Note `AgentOsProxyMockTest`,
   `JiraProxyHttpTest` integrate through the generic handler.

---

## 4. AXE 2c — The Node forge chain and `forge-epic.mjs` callers

### 4.1 Node forge modules

- `factory/workflows/forge-epic.mjs` — thin "vertical Forge workflow": reads
  `FACTORY_FORGE_RUN_REQUEST`/`FACTORY_FORGE_RUN_FIXTURE`, resolves roots, calls `createEpicRun(...)`,
  logs a single `create-epic-run` phase. Imports `../lib/forge-roots.mjs` + `../lib/forge-ledger.mjs`.
- `factory/lib/forge-*.mjs` (13 files) — **all stateless compatibility facades** re-exporting
  `../runtime/factory-operational.mjs` (the generated bundle): `forge-bmad-reader`, `forge-front-oracle-resolution`,
  `forge-g2`, `forge-human-decision`, `forge-ledger`, `forge-roots`, `forge-spec`, `forge-story-analysis`,
  `forge-story-edit`, `forge-story-oracles`, `forge-story-spec`, `forge-workflow-adapter`,
  `forge-workflow-sync`.
- `factory/src/domain/forge-bmad/*` (`forge-bmad-parser`, `forge-human-decision`, `forge-ledger`,
  `forge-roots`, `forge-spec`, `forge-story-spec`, `forge-workflow-adapter`, `types`, `jira`),
  `factory/src/application/forge-bmad/*` (`forge-front-oracle-resolution`, `forge-g2`,
  `forge-human-decision`, `forge-story-analysis`, `forge-story-edit`, `forge-story-oracles`,
  `forge-workflow-sync`), `factory/src/adapters/forge/*` (`forge-bmad-file-reader`, `forge-ledger-store`,
  `forge-roots-resolver`, `forge-spec-reader`), `factory/src/adapters/jira/jira-client.ts`.
- `factory/src/entrypoints/factory-operational.ts:156-186` re-exports **all** forge modules into the
  generated bundle `factory/runtime/factory-operational.mjs` (git-tracked, rebuilt by
  `factory/toolchain/build.mjs`).
- `factory/src/domain/forge-bmad/forge-ledger.ts:19` → `FORGE_WORKFLOW_VERSION = 'forge-epic-v1'`
  (mirrors Kotlin `ForgeLedger.kt:17`).

### 4.2 Real callers of `forge-epic.mjs`

Registration / tests only:
- `factory/run.mjs:70` — dispatch table entry `'workflow:forge-epic'` (and usage hints L12, L130).
- `factory/tests/test-run-dispatch.mjs:33,112-116,232-233` — asserts the dispatch entry and module exist.

**No production caller found** that spawns `node factory/run.mjs workflow forge-epic` (grep across the
whole repo excluding `node_modules`/`.git`/`.nx`). The two actual EpicRun creators are in-process:
- Node control plane: `factory/dashboard/forge-routes.mjs:281` calls `createEpicRun(...)` directly
  (same pattern as `:57` importing the ledger functions).
- Kotlin: `factory-service/.../forge/service/ForgeRunService.kt` `createEpicRun(...)` → `ledgerStore.createEpicRun`.
- `LegacyRunService.kt:219-226` spawns `node <runEntry> <workflow>` for whatever workflow the API request
  names; `runEntry` default `factory/run.mjs` (`ForgeProperties.kt:18`). `forge-epic` would only be
  launched if a client explicitly requested it — nothing does.

⇒ **`forge-epic.mjs` is legacy/orphaned** (still dispatchable manually and still bundled). For §6:
it can be retired/kept as a manual tool; it does **not** gate the W6b cut. But `forge-roots.mjs` /
`forge-ledger.mjs` facades are imported by it and by `oracles/run-frontend-verification.mjs` uses
`forge-front-oracle-resolution.mjs` (per `docs/factory-node-cut-inventory.md` §4.2).

### 4.3 `forge_bmad/` (19 files) — Coday project overlay, fully dev-specific

`forge_bmad/coday/` contains: `agents/ProductEngineer.yaml`, `prompts/*.yml` (run-forge, run-factory),
`skills/bmad/**` and `skills/core/factory-workflow-projection/SKILL.md`,
`integrations/PROJECT_SCRIPTS.yaml`, and `scripts/*.ts`:
`forge-factory-launch.ts`, `forge-gate-run.ts` (standalone gate state machine, Gates 1-4),
`forge-gate2-record.ts`, `forge-run-recon.ts`, `forge-run-yaml-updater.ts`, `forge-workflow-sync.ts`
(+ `.spec.ts` tests). These call the Factory HTTP API at `http://localhost:3141`
(`PROJECT_SCRIPTS.yaml:45,68-71,78`; `forge-factory-launch.ts:31`), not `forge-epic.mjs`.
This is exactly the kind of artifact a "forge domain plugin" would ship (domain skills/agents/scripts).

---

## 5. Generic core primitives (candidate extension points for §3 of the design)

Packages under `factory-service/src/main/kotlin/io/whozoss/factory/` (NOT forge/runs):

| Package | Main types | Primitive exposed |
|---|---|---|
| `workflow` | `WorkflowService`, `WorkflowController`, `WorkflowDefinitionController`, `WorkflowSseController`, `WorkflowSseHub`, `JdbcWorkflowRepository`, `WorkflowInstanceRecord`, `WorkflowDefinitionRecord`, `WorkflowProjectionRecord`, `WorkflowProjectionValidator`, `WorkflowTransitionPolicy`, `HumanInteractionRecord`, `WorkflowEvidenceItem`, `WorkflowCodeTransitionRecord` | Workflow engine, definitions, projections, evidence, human interactions, SSE projection stream |
| `workunit` | `WorkUnit`, `WorkUnitState`, `WorkUnitService`, `WorkUnitRepository` | Work-unit lifecycle |
| `lease` | `WorkUnitLease`, `LeaseStatus`, `LeaseService`, `AcquireLeaseResult`, `LeaseRepository` | Leases / fencing |
| `artifact` | `ArtifactService`, `ArtifactAdminService/Controller`, `ArtifactStore`, `ArtifactBlobClient`, `S3/InMemory` clients, `ArtifactMetadata`, retention/availability | Artifact storage + governance |
| `oracle` | `OracleDefinitionRegistry`, `OracleExecutionService`, `OracleExecution`, `OracleController`, `OracleArtifact/EvidencePublisher`, `OracleDefinitionValidator` | Oracle registry + execution + evidence publishing |
| `agentattempt` | `AgentStepResultService/Controller`, `OutboxDrainService`, `AgentStepAttemptRecord`, `IdempotencyRepository`, `JdbcAgentStep*Repository` | Agent-step attempts, step results, idempotency, outbox |
| `delivery` | `DeliveryService`, `DeliveryOperationService/Controller`, `DeliveryTargetRegistry`, `DeliveryGitControlPlane`, `DeliveryPullRequestAdapter`, `DeliveryEvidenceStore` | Delivery + ports |
| `worker` | `Worker`, `WorkerState`, `WorkerService`, `WorkerRepository` | Worker registry |
| `environment` | `WorkUnitEnvironmentService/Controller`, `WorkEnvironment`, `GitProvisioner` (+`FakeGitProvisioner`) | Work-unit environments |
| `persistence` | `TenantScope`, `TenantScopeProvider`, `ScopedRepository` | Tenancy (per-repository scoping) |
| `web` | `TrustContext`, `TrustContextFilter/Extractor/ArgumentResolver`, `MembershipResolver`, `FakeIdp`, `CorrelationIdFilter`, `AdminGuard`, `RawSseEvent` | HTTP boundary / identity / SSE framing |
| `config` | `WebConfig`, `OpenApiConfig`, `JacksonConfig`, `FactoryProperties` | Wiring/contract |
| `error` | `FactoryException`, `ConflictException`, `BadRequestException`, `ErrorEnvelope`, `FactoryExceptionHandler` | Error contract |

These are the stable primitives the forge plugin should hook (the design's SPI proposal should be
derived from the §2.2 consumed set plus workflow/agent-step/oracle/artifact projections).

---

## 6. Existing docs / specs to align with (and to cite)

- `docs/factory-node-cut-inventory.md` (538 lines) — the **W6b cut inventory** (read-only scout report,
  already committed). Directly relevant: §2.2 lists forge `lib/*.mjs` as control-plane A; §4.2 is the
  forge `forge-epic.mjs` decision (options 1/2) and flags `oracles/run-frontend-verification.mjs`;
  §5.6 lists external consumers on `:3141`; §6.2 gives the recommended W6b sequence; §6.4 confirms
  `factory/infra/migrations` V1→V7 == factory-service V1→V7 (orphan).
- `app_docs/453164ec_forge-bmad-runs.md` — **W6a** summary: forge/BMAD + legacy runs ported to Kotlin;
  lists every main file; states no migrations changed and JSONL stays outside Postgres.
- `specs/453164ec_forge_bmad_runs_kotlin_port.md` — the **A8 port plan**; constraints: porting only, do
  not wire cockpit or delete Node control plane (deferred to W6b), reuse `workstreams` schema, JSONL
  ledger invariants, SSE parity, OpenAPI regen.
- `specs/6786691c_factory_service_socle.md` — **W0** socle plan (empty core, no aggregate).
- `specs/90527113_extract_factory_bridge_plugin.md`, `specs/f5e09c98_extract_factory_bridge_plugin.md`
  — the **plugin-extraction precedent** (Factory bridge out of AgentOS core into PF4J plugin).
- `docs/study/software-factory.md` — reference architecture of the Node orchestrator (instrument B),
  defines the frontière BMAD (agents never see oracles/registry) — useful framing for "domain plugin".
- `factory/*.md` — ARCHITECTURE, AUTHORITY_SOURCES, DEPENDENCY_MATRIX, PERSISTENCE_SWITCH_ROLLBACK,
  WORKFLOW_PROJECTION, WORK_UNIT_ENVIRONMENT, FORGE_WORKFLOW_ADAPTER (all describe control-plane A;
  §4.9 of the cut inventory says rewrite/delete).
- `coday.yaml:99-106` — only a `deploy-plugins` target (AgentOS), **no factory plugin concept exists**.
- `docs/` exists and is the target folder for the deliverable; naming convention is kebab-case `.md`.

---

## 7. Other consumers / boundary facts useful for the design

- `factory-service/project.json` — Nx application, tags `["type:app","platform:jvm","scope:service"]`,
  targets build/test/bootRun/bootJar/generate-openapi-spec/check-openapi-spec. `build.gradle.kts` is an
  **independent** build ("this service is not a composite build of agentos", `settings.gradle.kts:9-10`).
  ⇒ A Factory forge plugin would need its own `settings.gradle.kts`/`includeBuild` wiring, mirroring
  `agentos/settings.gradle.kts`.
- CI: `.github/workflows/validate.yml` — TS affected lint/test (L39), JVM build+test via
  `nx show projects --projects="tag:platform:jvm"` (L68-77), and `check-openapi-spec` (L82).
- Product/agent consumers of the Factory API (currently `:3141`), from cut-inventory §5.6:
  `apps/client/proxy.conf.json`, `libs/integration/src/lib/factory.tools.ts` (+ schemas/types/validation/tests),
  `libs/model/src/lib/project-description.ts`, `agentos/agentos-factory-bridge-plugin/.../FactoryBridgeConfig.kt`,
  `forge_bmad/coday/scripts/*.ts` and `PROJECT_SCRIPTS.yaml` / skills.
- `factory-service` has **no** `pf4j`/ServiceLoader/plugin dependency today (grep-verified) — adding one
  is a genuine new capability, not an existing seam.

---

## 8. Open questions the design doc must resolve (surfaced by this recon)

1. **SPI vs. separate service**: AgentOS uses in-process PF4J. Should Factory forge be an in-process
   PF4J plugin (closest to the requested pattern) or a separately deployable service using the generic
   Factory HTTP API? The claimed target ("cœur générique + plugins de domaine, à la manière d'AgentOS")
   points to PF4J, but factory-service is an independent Gradle build, not a composite with agentos.
2. **Extension-point API**: derive from forge's consumed surface (§2.2) — e.g. `TrustContext`,
   `TenantScopeProvider`, `WorkflowProjectionValidator`, `RawSseEvent`, error contract — plus the
   generic primitives the forge plugin should *call* (workflow instance/projection, agent-step result,
   oracle execution/publishers, artifact). Define which are `ExtensionPoint` (plugin contributes) vs
   plain exported API (plugin consumes).
3. **Workstreams ownership**: generic table+API (`WorkstreamController`/`JdbcWorkstreamRepository`)
   currently under `forge/` — move to core (recommended, since `workstream_id` is in every PK and in
   `factory.tenant.workstream-id`).
4. **Legacy runs (`runs/**`)**: `LegacyRunService` spawns the Node instrument and is the bridge to B for
   W6b. It is not "forge domain" (it is the generic run/review-gate protocol) but contains Node/Jira/3141
   coupling. Decide core vs plugin vs transitional.
5. **OpenAPI composition**: single `factory-openapi.yaml` vs per-module spec/groups; impact on
   `check-openapi-spec`.
6. **`forge-epic.mjs`**: retire (no caller), keep as manual diagnostic, or part of the plugin's Coday
   overlay. See §4.2 / §6 of this file and cut-inventory §4.2.

---

## 9. Supplement — subagent #5 (design/plan context) confirmations

All paths below verified to exist.

### 9.1 Aggregate → spec/app-doc inventory (no single A1..A8 doc exists)

| ID | Spec (plan) | app_doc (summary) | Kotlin package |
|---|---|---|---|
| A1 (artifacts, unnumbered) | `specs/cfa7e583_artefacts_aggregate_port.md` | `app_docs/cfa7e583_artifact-aggregate-port.md` | `artifact` |
| A2 | `specs/9c104d1e_oracles_aggregate_kotlin_port.md` | `app_docs/9c104d1e_oracles-aggregate.md` | `oracle` |
| A3/A4 (leases/workers/environments) | `specs/5f5e940e_leases_workers_kotlin_port.md` | `app_docs/5f5e940e_leases-workers-environments.md` | `lease`, `worker`, `environment` |
| A5 | `specs/5de96b0e_delivery_aggregate_kotlin_port.md` | `app_docs/5de96b0e_delivery-aggregate-kotlin.md` | `delivery` |
| A6 | `specs/48dd9ddf_workflow_sse_aggregate_a6_port.md` | `app_docs/48dd9ddf_workflow-sse-aggregate.md` | `workflow` (+ `workflow/sse`) |
| A7 | `specs/12a3ae7b_agent_step_results_outbox_port.md` | `app_docs/12a3ae7b_agent-step-outbox.md` | `agentattempt` |
| A8 | `specs/453164ec_forge_bmad_runs_kotlin_port.md` | `app_docs/453164ec_forge-bmad-runs.md` | `forge`, `runs` |
| W0 (socle) | `specs/6786691c_factory_service_socle.md` | `app_docs/6786691c_factory-service-socle.md` | `config`, `web`, `error`, `persistence` |
| W6a fix | `specs/b662c602_fix_macos_forge_tests.md` ("W6a scope") | — | — |

- Only **W6a** (Kotlin forge port, branch `integration/factory-kotlin-w6a`) and **W6b**
  (`docs/factory-node-cut-inventory.md`) are explicitly labelled. Current branch:
  `sbx/coday-cut-inventory-b428`.
- A1 is not literally labelled "A1"; it fills the artifacts slot. Caveat: `docs/study/software-factory.md`
  reuses A1/A2/A5/A8 for actions/gates, not aggregates.

### 9.2 Closest existing "generic core + forge adapter" designs (important for §3)

- **`factory/WORKFLOW_PROJECTION.md`** already articulates the target shape in prose:
  - Stage 1 is an explicitly **"generic, Forge-independent workflow projection contract and filesystem
    store"** (L25) — no HTTP/AgentOS/UI/watcher/Git/worktree.
  - Canonical generic entry `/run-factory <ref> [--workflow=<workflowType>]`; the generic protocol
    "assumes neither a source system nor an identifier syntax, ticket model, software lifecycle, or
    BMAD" (L29).
  - **Domain declaration model** (L31-33): each declaration supplies `workflowType`, compatibility
    criteria, stable identity rule, sole publisher role, and lifecycle source (v2 graph). Workflow
    identity is opaque to Factory. Publication is revision-checked (`expectedRevision`), sole-publisher.
  - `/run-forge` is described as **"the BMAD adapter alias for `/run-factory --workflow=bmad-story`"**
    (L35); Jira identity, reconnaissance, workstream confirmation, BMAD gates are domain-specific and
    "do not alter the generic contract". This is exactly the core/plugin separation the mission wants —
    the doc already names it an *adapter*.
- **`factory/FORGE_WORKFLOW_ADAPTER.md`** documents the deterministic Forge→generic pipeline:
  `Forge YAML → strict reader → deterministic Forge adapter → generic WorkflowProjection store →
  workflow-projection-updated SSE → generic Angular workflow state` (L7-12). The Forge cockpit keeps
  its own JSONL run ops; the former Forge-specific Gantt projection was removed (L23).

### 9.3 Build / Nx facts (confirmed)

- `coday.yaml` does **not** mention the Factory control plane or factory-service; its scripts are `nx`,
  `gradle` (cwd `agentos/`), `deploy-plugins`, `web-dev`.
- `factory-service/build.gradle.kts`: `group io.whozoss.factory`, description "Factory Service —
  autonomous Kotlin/Spring Boot backend for the Factory"; Java/Kotlin 25, Spring Data JDBC (not JPA),
  Flyway, springdoc; OpenAPI gen port 18141, dev port 8141.
- `factory-service/project.json`: Nx application, tags `["type:app","platform:jvm","scope:service"]`,
  tests via `pnpm nx test factory-service`. No README/design.md in `factory-service/`.

### 9.4 "plugin" in the Factory context

- No file contains the literal phrase "forge plugin" or "generic Factory core" — the concept exists
  only as "generic/Forge-independent protocol" + "Forge adapter" (9.2). No Factory plugin
  infrastructure exists in code (confirmed §0/§3 of this file).

---

## 10. Supplement — subagent #3 (generic core + coupling grep) confirmations

Verified first-hand; adds precision to §3 and §5.

### 10.1 Generic core surface (precise, per package)

- **workflow** — engine: `WorkflowService.kt`, port `WorkflowRepository` (+`WorkflowEvidenceRepository`,
  `HumanInteractionRepository`) impl `JdbcWorkflowRepository.kt`. Web: `WorkflowController.kt`
  (`/api/factory/workflows`), `WorkflowDefinitionController.kt` (`/api/factory/workflow-definitions`).
  SSE: `WorkflowSseController.kt` + `WorkflowSseHub.kt`, named events
  `workflow-projection-updated|removed|restored|purged` (`WorkflowSseHub.kt:15-19`), 30s heartbeat.
  Projection validation: `WorkflowProjectionValidator` (also imported by forge `WorkflowSync.kt`).
- **workunit** — `WorkUnit`/`WorkUnitState`/`WorkUnitService`/`WorkUnitRepository`; no web controller.
- **lease** — `WorkUnitLease`/`LeaseStatus`/`LeaseService`/`LeaseRepository`; no web controller.
- **artifact** — `ArtifactService`/`ArtifactAdminService`, ports `ArtifactStore` + `ArtifactBlobClient`
  (InMemory/S3), `PostgresArtifactStore`, config `@ConfigurationProperties("factory.artifact")`,
  web `/api/factory/admin/artifacts`.
- **oracle** — `OracleDefinitionRegistry`, `OracleExecutionService`, publishers `OracleArtifactPublisher`
  / `OracleEvidencePublisher`, port `OracleExecutionRepository : ScopedRepository`, web
  `OracleController` @ `/api/factory/workflows` (oracle run).
- **agentattempt** — `AgentStepResultService`, `OutboxDrainService` (outbox primitive), ports
  `AgentStepAttemptRepository`/`AgentStepResultRepository`/`IdempotencyRepository`, web
  `AgentStepResultController` @ `/api/factory/agent-step-results`.
- **delivery** — `DeliveryService`/`DeliveryOperationService`, ports `DeliveryEvidenceStore`,
  `DeliveryGitControlPlane`, `DeliveryPullRequestAdapter`, `DeliveryTargetRegistry`, config
  `@ConfigurationProperties("factory.delivery")`, web `/api/factory/workflows/{workflowId}/delivery`
  (checkpoint/push/pull-request/promote/evidence/deploy/verify/reconcile/rollbacks).
- **worker** — `Worker`/`WorkerState`/`WorkerService`/`WorkerRepository`; no web controller.
- **environment** — `WorkUnitEnvironmentService`, port `GitProvisioner` (+fake), web
  `/api/factory/workflows/{workflowId}/environment`.
- **config** — `FactoryProperties @ConfigurationProperties("factory")` binds only `bind`/`tenant`/
  `security` — **no forge field**; `OpenApiConfig` builds the spec from the auto-scanned controllers and
  **does not name forge** (the OpenAPI forge coupling is purely the scanned `forge/web` controllers).
- **persistence** — `TenantScope`, `TenantScopeProvider`, `ScopedRepository` (tenancy kernel).
- **web** — `TrustContext`, filters/resolvers, `CorrelationIdFilter`, `AdminGuard`, `RawSseEvent`.

### 10.2 Coupling grep confirmations

- **Upward dependency generic→forge: none** (re-confirmed).
- **forge→generic imports** exactly as §2.2 (TrustContext, TenantScopeProvider, TenantScope,
  ScopedRepository, RawSseEvent, WorkflowProjectionValidator, ProjectionValidation, FactoryException,
  ConflictException, BadRequestException).
- **No SQL/migration forge coupling**: migrations V1–V9 contain no forge-named object; `workstream*`
  tables are generic (V2/V3). Forge durable state is JSONL on disk.
- **No `@ComponentScan`/`scanBasePackages` anywhere**; the only heart-side forge coupling in code
  topology is the default package scan of `@SpringBootApplication`.
- Config coupling is confined to `application.yml` `factory.forge.*` and
  `forge/config/ForgeProperties.kt @ConfigurationProperties("factory.forge")`.

---

## 11. Supplement — subagent #4 (Node forge chain / forge-epic callers) confirmations

Verified first-hand; adds precision to §4.

### 11.1 `factory/workflows/` contents (full)

- `.mjs` workflows: `fix-loop.mjs`, `us-loop.mjs`, `forge-epic.mjs` (only forge workflow; 33 lines).
- **JSON domain workflow declarations** (separate from the `.mjs` dispatcher; loaded by
  `factory/lib/workflow-definition-registry.mjs` scanning `factory/workflows/<type>/<version>.json`):
  - `factory/workflows/bmad-story/1.0.0.json` (`workflowType: "bmad-story"`),
  - `factory/workflows/bmad-story/1.1.0.json` (`workflowType: "bmad-story"`),
  - `factory/workflows/bmad-story-frontend/1.0.0.json` (`workflowType: "bmad-story-frontend"`) + `briefs/`.
  - These are the *declarative* domain artifacts the mission's "forge plugin" would own; `forge-epic.mjs`
    is NOT part of this registry.

### 11.2 `forge-epic` dispatch / callers

- Sole runtime registration: `factory/run.mjs:70` `'workflow:forge-epic'` in the `DISPATCH` table
  (L64-81). No `alias:forge-epic` exists. Loaded by `await import(path)` and invoked `module.run(log)`.
- Callers that spawn `run.mjs` generally (not `forge-epic` specifically):
  `factory/dashboard/run-routes.mjs:288-292` (Node server A) and
  `factory-service/.../runs/service/LegacyRunService.kt:224` (Kotlin) — both take the workflow from the
  request. **No code passes `workflow forge-epic`**; execution is manual/direct only. Confirms the
  "orphaned" finding.

### 11.3 `factory/lib/forge-*.mjs` export surface (for the plugin's public API)

Each is a pure re-export facade over `factory/runtime/factory-operational.mjs`. Useful named exports:
- roots: `FORGE_ROOTS_SCHEMA_VERSION`, `DEFAULT/EXTERNAL/REPO_RUN_STORE_POLICY`, `resolveForgeRoots`,
  `defaultRunStoreRoot`, `ensureForgeRunStore`;
- ledger: `FORGE_LEDGER_SCHEMA_VERSION`, `FORGE_WORKFLOW_VERSION`, `createEpicRun`, `parseForgeLedger`,
  `projectForgeRun`, `listForgeRunProjections`;
- spec/story-spec: `FORGE_SPEC_SCHEMA_VERSION`, `G2_POLICY_VERSION`, `ORACLE_CATALOG`, `loadForgeSpec`;
  `FORGE_STORY_SPEC_SCHEMA_VERSION`, `G2_US_POLICY_VERSION`, `validateInheritance`, `readStorySpec`, `hashStorySpec`;
- gates/decisions: `evaluateG2`, `evaluateG2US`; `G1_POLICY_VERSION`, `computeG1EvidenceSetHash`,
  `recordHumanDecision`;
- story ops: `executeStoryAnalysis`, `executeStoryEdit`, `executeStoryOracles`,
  `isAllowedStoryOracleRequestBody`;
- adapter/sync: `FORGE_WORKFLOW_ERROR_CODES`, `adaptForgeRunToWorkflowProjection`;
  `SAFE_FORGE_TICKET_ID`, `sanitizeForgeSyncAttribution`, `syncForgeWorkflowProjection`.

### 11.4 Node forge source layout (mirrors the Kotlin classes 1:1)

- `factory/src/domain/forge-bmad/`: `types`, `forge-bmad-parser`, `forge-human-decision`, `forge-ledger`,
  `forge-roots` (`defaultRunStoreRoot = <repoRoot>/forge/factory-runs/`, L56), `forge-spec`,
  `forge-story-spec`, `forge-workflow-adapter`, `jira` (pure, no I/O).
- `factory/src/application/forge-bmad/`: `forge-front-oracle-resolution`, `forge-g2`,
  `forge-human-decision`, `forge-story-analysis`, `forge-story-edit`, `forge-story-oracles`,
  `forge-workflow-sync` (orchestration + I/O).
- `factory/src/adapters/forge/`: `forge-bmad-file-reader`, `forge-ledger-store` (JSONL append-only,
  `<runId>.jsonl`), `forge-roots-resolver`, `forge-spec-reader`.

### 11.5 Workstreams / JSONL

- Forge durable state = append-only JSONL under the target repo's `forge/factory-runs/<runId>.jsonl`
  (Node adapter + Kotlin `FileForgeLedgerStore`). No forge-named Postgres table; `workstreams` is the
  generic tenant table (V2).

---

## 12. Supplement — subagent #2 (Kotlin forge aggregate) confirmations

Verified first-hand; adds constants and a strict dependency statement to §2.

### 12.1 Strict dependency statement

The `forge`/`runs` packages import **only four groups** of generic factory types:
`workflow.domain.{ProjectionValidation, WorkflowProjectionValidator}`,
`persistence.{TenantScope, TenantScopeProvider, ScopedRepository}`,
`web.{TrustContext, RawSseEvent}`, and `error.{FactoryException, ConflictException, BadRequestException}`.
They import **nothing** from `workunit`, `lease`, `artifact`, generic `oracle`, `agentattempt`,
`delivery`, `environment`, `worker` or the outbox — even though those packages live in the same module.

### 12.2 Policy-version constants stamped in the forge domain (dev-specific fingerprints)

- `FORGE_SPEC_SCHEMA_VERSION = 1`, `G2_POLICY_VERSION = "forge-g2-deterministic-v1"`,
  `ORACLE_CATALOG = {front.build, front.tests, back.build}` (`ForgeSpec.kt`).
- `FORGE_STORY_SPEC_SCHEMA_VERSION = 1`, `G2_US_POLICY_VERSION = "forge-g2-us-deterministic-v1"` (`ForgeStorySpec.kt`).
- `FORGE_LEDGER_SCHEMA_VERSION = 1`, `FORGE_WORKFLOW_VERSION = "forge-epic-v1"` (`ForgeLedger.kt:17`).
- `FRONT_ORACLE_MAP_SCHEMA_VERSION = 1`; front oracle resolution spawns `pnpm nx show project`
  (`ForgeFrontOracleResolution.kt:99-116`).
- G1: `G1_OUTCOMES = {approved, rejected}` (`ForgeHumanDecision.kt`); Jira `COMMENTS_CHAR_BUDGET = 8000` (`JiraDomain.kt`).

### 12.3 Persistence split (definitive)

- **Only Postgres-backed thing in forge** = `workstreams` table, via
  `forge/infrastructure/JdbcWorkstreamRepository.kt` (`@Repository`, `NamedParameterJdbcTemplate`,
  `ScopedRepository`/`TenantScope`, JSONB `payload.status`). The table pre-exists (V2) and is never
  recreated.
- **Everything else is filesystem**: forge EpicRun/JSONL ledger (`FileForgeLedgerStore` writes
  `<runStoreRoot>/<runId>.jsonl`, default `<repo>/forge/factory-runs/`), spec files, Markdown.
  `HttpAgentOsProxyClient` and `AgentOsProxyClient` also hardcode `forge/factory-runs` (L70 / L28).

### 12.4 Route surface owned by the forge aggregate (to move with the plugin)

- `/api/forge/runs…` + `/api/factory/forge/runs…` (list, create, G1/G2 read+evaluate, story
  executions/edits/oracles) — `ForgeRunController.kt`.
- `/api/jira/{ticketId}` + `/api/factory/jira/{ticketId}` — `JiraProxyController.kt`.
- `/api/agents`, `/api/cases/{caseId}/events` — `AgentOsProxyController.kt` (borderline: generic proxy).
- `/api/factory/workstreams` — `WorkstreamController.kt` (borderline: generic tenant API).
- `/api/runs…` + `/api/factory/runs…` incl. `/{id}/stream` SSE and `/{id}/review-gate[/reply]` —
  `runs/web/LegacyRunController.kt` + `LegacyRunSseController.kt`.

---

## 13. Supplement — subagent #1 (AgentOS plugin infra) — precise contract facts

Verified first-hand; adds exact line numbers and boot mechanics to §1.

### 13.1 The six extension points (all `: org.pf4j.ExtensionPoint`)

| Interface | File:line | Key method |
|---|---|---|
| `ToolPlugin` | `sdk/tool/ToolPlugin.kt:54` | `provideTools(config, configName, context): List<StandardTool<*>>` (abstract, :101); `describeNamespace` default null |
| `UserContextProvider` | `sdk/scheduledPrompt/UserContextProvider.kt:33` | `provideUserContext(...): UserContextResult` |
| `ToolGrantPolicy` | `sdk/spi/ToolGrantPolicy.kt:52` | `evaluateToolGrant(...)`: `Neutral`/`AllowOnly`/`Deny` (default Neutral) |
| `ExternalExecutionContextProvider` | `sdk/spi/ExternalExecutionContextProvider.kt:26` | `provideExecutionContext(...): Map<String,Any?>` (default empty) |
| `AnswerInterceptor` | `sdk/spi/AnswerInterceptor.kt:42` | `interceptAnswer(...)`: `Accept`/`Reject` (default Accept) |
| `CaseLifecycleObserver` | `sdk/spi/CaseLifecycleObserver.kt:27` | `onStatusChanged`, `onEventStored` (no-op) |

Only `ToolPlugin.provideTools` is abstract; the rest are safe no-op/pass-through — the isolation
contract to reproduce.

### 13.2 Boot / discovery mechanics

- `PluginConfiguration` `@Bean pluginManager` (`config/PluginConfiguration.kt:28-41`) builds
  `NullSafeSpringPluginManager`, sets `applicationContext`.
- pf4j-spring `SpringPluginManager.init()` is `@PostConstruct`: `loadPlugins()` → `startPlugins()` →
  `new ExtensionsInjector(...).injectExtensions()`, which calls
  `beanFactory.registerSingleton(extensionClass.getName(), extension)` for every `@Extension` (this is
  how `List<AnswerInterceptor>` etc. get populated). Plugin JARs are **not** component-scanned; they
  live in a separate classloader and are registered by pf4j-spring.
- `ToolRegistryService` (`tool/ToolRegistryService.kt:30-40,66-67`): injects `List<ToolPlugin>`
  (Spring-internal plugins) **and** `pluginManager.getExtensions(ToolPlugin::class.java)` (PF4J
  plugins); internal registered first, PF4J may override by `integrationType` (last-write-wins).
- REST control plane: `plugin/api/PluginController.kt` (`/api/plugins`: list, upload, start/stop/reload,
  delete, debug; SUPER_ADMIN except GETs).

### 13.3 Config keys / enable-disable

- `AgentOsPluginsConfigProperties` prefix **`agentos.plugins`**, single `dir`, default `"plugins/"`
  (`config/PluginConfigProperties.kt:5-11`); `application.yml:210-211` (`${PLUGINS_DIR:plugins/}`);
  `application-docker.yml:38-39` (`/app/plugins/`). Directory auto-created.
- **No allow-list toggle**: plugins auto-load from the directory; disabling = remove/rename the JAR or
  runtime stop/unload. `.env.example:20-23` lists `PLUGINS_AUTO_LOAD` / `PLUGINS_DEV_MODE` but **no code
  references them** (unused/aspirational) — do not rely on them in the design.
