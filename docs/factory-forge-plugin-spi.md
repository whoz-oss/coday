# Scout findings — W7.1 input for `docs/factory-forge-plugin-spi.md`

> Read-only recon. Every fact below was verified first-hand with read/grep/find.
> Paths relative to `/work/app`. HEAD = `46c64db7cd8ab32a9ad3738f5f9aa665930825c5`, branch `sbx/coday-cut-inventory-b428`.
> The deliverable `docs/factory-forge-plugin-spi.md` does **not exist yet**; `docs/factory-generic-core-forge-plugin-design.md`
> (committed `46c64db7`) is itself the full recon report (13 sections) and is the primary reference. This file
> complements it with the extra precision needed for the *SPI contract + packaging* deliverable.

---

## 0. TL;DR decisions the design doc must encode (facts, not opinions)

1. **AgentOS precedent for REST routes: there is NO plugin-contributed REST in AgentOS.** The host owns the
   transport; plugins own only decision/logic. The bridge plugin's "controller"
   `agentos/agentos-factory-bridge-plugin/.../FactoryStepResultBindingController.kt` is a **plain class** (no
   `@RestController`) whose KDoc says verbatim: *"The plugin does not own an HTTP server; the AgentOS host exposes
   the transport (the original Spring `@RestController`) and maps these outcomes onto status codes."* The host
   route was deleted from agentos-service in T2c (`specs/2faf6299_cleanup_factory_bridge_residues.md`) and **never
   re-implemented** (grep of `StepResultBinding` across `agentos/agentos-service` = 0 hits). ⇒ Factory must
   *invent* its own plugin→MVC route mechanism (Q3 has no precedent to copy).
2. **SDK "no Spring Boot" is achievable for pure kernel types, but three of the requested types are Spring-tied:**
   `TenantScopeProvider` is a concrete `@Component` bound to `FactoryProperties`; `RawSseEvent` implements
   `org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder`. `WorkflowProjectionValidator`
   is a pure Kotlin `object`, `TrustContext`/`TenantScope`/`ScopedRepository`/`error.*` are pure. The doc must
   split "pure kernel → SDK" vs "core service contracts the plugin consumes (interfaces defined in SDK, implemented
   in core)".
3. **Core→forge code coupling is exactly zero** (grep-verified), but there is a **runs↔forge coupling**:
   `runs/web/LegacyRunController.kt` and `runs/web/LegacyRunSseController.kt` import
   `io.whozoss.factory.forge.web.{forgeError, resolveForgeCaller}`, and `forge/config/ForgeConfiguration.kt`
   declares the `legacyRunService` bean (class from `runs/`). The plugin cut must handle runs too.
4. **OpenAPI determinism**: `check-openapi-spec.sh` git-diffs a *single* generated file
   `factory-service/openapi/factory-openapi.yaml`. PF4J plugin classes live in a **separate classloader** and are
   not on the app classpath ⇒ springdoc cannot see plugin controllers by default. Recommended: **core spec =
   core-only**, plugin ships its own spec + own Nx check target. Never let the core spec's content depend on
   whether the plugin JAR is present.
5. **Nx integration gap**: the Gradle→Nx inference plugin `tools/plugins/agentos-gradle/src/agentos-gradle.ts`
   only globs `agentos/*/build.gradle.kts` and requires a sibling `project.json`. A factory plugin outside
   `agentos/` gets **no Nx targets automatically** — it needs a hand-written `project.json` (or a generalized
   glob) to participate in `nx affected` / CI `tag:platform:jvm`.

---

## 1. `factory-service` today (facts for §2/§5/§6)

### 1.1 Build / runtime
- **Independent Gradle build**, explicitly not an agentos composite: `factory-service/settings.gradle.kts:9-10`
  ("Independent build: this service is not a composite build of agentos"). Own version catalog
  `factory-service/gradle/libs.versions.toml`.
- Versions (`libs.versions.toml`): java/kotlinJvmTarget **25**, kotlin **2.3.20**, Spring Boot **3.5.9**,
  springdoc **2.8.9**, springdoc gradle plugin **1.9.0**, Jackson **2.19.4**, klogger **2.0.4**, Testcontainers 1.20.4.
- `factory-service/build.gradle.kts`: spring-boot-starter-web/actuator/data-jdbc, Flyway+Postgres, springdoc
  starter, H2 (openapi profile only). **No pf4j, no plugin infra today** (grep-verified).
- `FactoryServiceApplication.kt:15-17`: `@SpringBootApplication` + `@ConfigurationPropertiesScan` on
  `io.whozoss.factory` ⇒ **implicit component scan** of `forge/**` and `runs/**`. **No `@ComponentScan` /
  `scanBasePackages` anywhere** (grep-verified). This is the #1 wiring coupling to break.
- App port 8141 (`application.yml`); openapi generation fork on **18141** (`build.gradle.kts openApiGenPort`).
- `factory-service/project.json`: Nx application, tags `["type:app","platform:jvm","scope:service"]`, targets
  build/test/bootRun/bootJar/**generate-openapi-spec**/**check-openapi-spec**.

### 1.2 Config
- `application.yml` has `factory.forge.*` (`agentos-url`, `runs-dir`, `run-entry`, `jira.*`) bound by
  `forge/config/ForgeProperties.kt` (`@ConfigurationProperties("factory.forge")`).
- Core `factory.*` tree (`config/FactoryProperties.kt`): only `bind`, `tenant`, `security` — **no forge field**.
- `TenantScopeProvider` (`persistence/TenantScopeProvider.kt`) is a `@Component` reading `FactoryProperties.tenant`
  — i.e. the tenancy service itself depends on core config.

### 1.3 OpenAPI pipeline
- `config/OpenApiConfig.kt`: only sets `Info` + an `operationId` customizer (`{method}{Entity}`). No groups,
  no package filter, no `springdoc.group-configs` anywhere.
- Generation: `springdoc-openapi-gradle-plugin` → `./gradlew generateOpenApiDocs` with `--spring.profiles.active=openapi`
  (`application-openapi.yml` = H2 + Flyway off) → writes `factory-service/openapi/factory-openapi.yaml`
  (`servers: http://localhost:18141`).
- Check: `factory-service/check-openapi-spec.sh` regenerates then `git diff --exit-code openapi/factory-openapi.yaml`.
  Nx target `check-openapi-spec` inputs = `{projectRoot}/src/main/kotlin/**/*.kt` + the yaml.
- CI: `.github/workflows/validate.yml:82` runs `nx affected --target=check-openapi-spec`.
- Spec content: 68 `/api` paths; forge-owned tags/paths present: `forge`, `agentos-proxy`, `jira`, `runs`,
  `workstreams` (tags at yaml lines 12-31; e.g. `/api/forge/runs...` L136+, `/api/factory/workstreams` L325+,
  `/api/runs` L83, `/api/jira/{ticketId}` L1940, `/api/agents` L2037, `/api/cases/{caseId}/events` L2018).

---

## 2. Q1 — Factory SDK module (contents, deps, publication)

### 2.1 The exact generic surface forge/runs consume (grep-verified)
Imports of `io.whozoss.factory.*` (excluding forge/runs packages) across `forge/**` + `runs/**`:

| Import | Count | File (first) |
|---|---|---|
| `persistence.TenantScopeProvider` | 7 | forge/web/ForgeHttp.kt:5, ForgeRunController.kt:12, JiraProxyController.kt:8, AgentOsProxyController.kt:7, WorkstreamController.kt:7, runs/web/LegacyRunController.kt:8, runs/web/LegacyRunSseController.kt:7 |
| `web.TrustContext` | 7 | same controller set + forge/web/ForgeHttp.kt:6 |
| `persistence.TenantScope` | 3 | forge/web/ForgeHttp.kt:4, forge/service/WorkstreamService.kt:6, forge/infrastructure/JdbcWorkstreamRepository.kt:5 |
| `persistence.ScopedRepository` | 1 | forge/infrastructure/JdbcWorkstreamRepository.kt:4 |
| `web.RawSseEvent` | 1 | runs/service/LegacyRunService.kt:5 |
| `workflow.domain.WorkflowProjectionValidator` | 1 | forge/domain/ForgeWorkflowAdapter.kt:4 |
| `workflow.domain.ProjectionValidation` | 1 | forge/domain/ForgeWorkflowAdapter.kt:3 |
| `error.FactoryException` | 1 | forge/web/ForgeHttp.kt:3 |
| `error.ConflictException` | 1 | forge/service/WorkstreamService.kt:4 |
| `error.BadRequestException` | 1 | forge/service/WorkstreamService.kt:3 |

**Reverse direction: zero** (`grep "import io.whozoss.factory.forge|...runs"` outside those packages = 0 hits).

### 2.2 Exact signatures / nature (to classify SDK vs core-service)
- `web/TrustContext.kt` — pure `data class` (principalId, principalType, organizationId, workstreamId, squadId,
  roles, scopes, correlationId, authenticationMethod, serviceIdentityId, loopback, namespaceId, caseId) +
  constants + `anonymous()`/`isKnownPrincipalType()`. **Pure → SDK candidate.**
- `persistence/TenantScope.kt` — pure `data class(organizationId, workstreamId)`. **Pure → SDK.**
- `persistence/ScopedRepository.kt` — pure generic interface (`findById`, `deleteById`). **Pure → SDK.**
- `persistence/TenantScopeProvider.kt` — **concrete `@Component`** depending on `FactoryProperties` +
  `TrustContext`; methods `defaultScope()` and `scopeOf(TrustContext?): TenantScope?`. **Not pure.** If the plugin
  needs it, SDK must expose an **interface** (e.g. `TenantScopeResolver`) implemented by core; keep the concrete
  class in core.
- `workflow/domain/WorkflowProjection.kt` — declares `ProjectionError`, sealed `ProjectionValidation`
  (`Valid(normalized: Map<String,Any?>)` / `Invalid(error)`), and `object WorkflowProjectionValidator` with
  `validate(input: Any?, expectedWorkflowId: String): ProjectionValidation`. Pure Kotlin (no Spring). **Pure → SDK.**
  Note it references `WorkflowErrorCodes`, `WorkflowStatuses`, `ResponsibilityKind` — these must travel with it
  (same file/package) or be minimized.
- `error/FactoryException.kt` — abstract `FactoryException(statusCode, errorCode, message, details, cause)` + concrete
  `BadRequestException(400)`, `UnauthenticatedException(401)`, `ForbiddenAdminRequiredException(403)`,
  `ResourceNotFoundException(404)`, `ConflictException(409)`, `RevisionConflictException(409)`,
  `UnprocessableEntityException(422)`. Pure Kotlin. **Pure → SDK.**
- `web/RawSseEvent.kt` — imports **Spring** `MediaType`, `ResponseBodyEmitter`, `SseEmitter`. **Not Spring-Boot-free.**
  If it must be shared, either (a) allow `spring-web` as `compileOnly` in the SDK (agentos-sdk allows only PF4J —
  this is a real divergence), or (b) keep `RawSseEvent` in core and let the plugin emit raw frames as `String`
  through an SPI. Design decision required.
- Also relevant (consumed indirectly): `error/ErrorEnvelope.kt` (`ErrorResponse`/`ErrorDetail`) and
  `error/FactoryExceptionHandler.kt` (`@RestControllerAdvice`, core-only).

### 2.3 Constraints to state
- `agentos-sdk` rule (`agentos/agentos-sdk/README.md:11`): **"Only PF4J - No Spring Boot, No Spring AI"**; `build.gradle.kts`
  deps = `api(libs.pf4j)`, `api(jackson.annotations)`, `api(jackson.module.kotlin)`, `api(kotlinx.coroutines.core)`,
  `compileOnly(jakarta.validation.api)`, `compileOnly(swagger.annotations)`.
- `agentos-sdk` publication: group `whoz-oss.agentos`, version from catalog `agentosSdk`, `maven-publish` to
  `mavenLocal()` + GitHub Packages (`pkg.github.com/whoz-oss/coday`), `withSourcesJar()/withJavadocJar()`.
- Its `settings.gradle.kts` is standalone, pulls the shared version catalog from `../gradle/libs.versions.toml`,
  repositories `mavenCentral`+`mavenLocal`.
- Proposed Factory SDK name is the doc author's choice; candidates consistent with existing naming: `factory-sdk`
  (mirrors `agentos-sdk`) or `factory-core-sdk`. Suggested group `io.whozoss.factory` (matches service) and
  coordinate `io.whozoss.factory:factory-sdk`.
- Distinguish three tiers in the doc: **(A) SDK pure kernel** (TrustContext, TenantScope, ScopedRepository,
  ProjectionValidation/Validator, error.*), **(B) SDK service contracts** (interfaces the plugin calls: projection
  publisher, run launcher, tenant-scope resolver, route contributor, raw-SSE frame sink), **(C) core-internal**
  (TenantScopeProvider concrete, RawSseEvent impl, MVC/Spring config, repositories, controllers).

---

## 3. Q2 — ExtensionPoints (minimum set)

### 3.1 AgentOS reference contract (to imitate in shape only)
All EP extend `org.pf4j.ExtensionPoint`; every method has a **safe default** (no-op / Neutral / Accept / empty map);
only `ToolPlugin.provideTools` is abstract (`sdk/tool/ToolPlugin.kt:54,101`). Callers catch exceptions:
- `ToolPlugin` — typed factory, `provideTools(config, configName, context): List<StandardTool<*>>`.
- `ToolGrantPolicy.evaluateToolGrant(...)` default `Neutral`, fail-open (`sdk/spi/ToolGrantPolicy.kt`).
- `ExternalExecutionContextProvider.provideExecutionContext(...)` default `emptyMap()`.
- `AnswerInterceptor.interceptAnswer(...)` default `Accept`.
- `CaseLifecycleObserver.onStatusChanged/onEventStored` no-op.
- `UserContextProvider` (`sdk/scheduledPrompt/`).
Resolution: `pluginManager.getExtensions(X::class.java)` (`tool/ToolRegistryService.kt:67`,
`plugin/UserContextProviderResolver.kt:30`) **and** `List<X>` Spring injection of the pf4j-registered singleton beans
(`caseFlow/CaseServiceImpl.kt:83,88,93,98`, `caseFlow/CaseRuntime.kt:90-91,201-210`, `agent/AgentExecutionContext.kt:41`).

### 3.2 What the forge plugin actually needs (from the design §2.2/§12.1 surface)
Only three capabilities are required by the mission: **(i) publish a workflow projection via
`WorkflowProjectionValidator`; (ii) expose its REST routes; (iii) contribute to run launching.**
Everything else forge does is self-contained (JSONL filesystem ledger, domain parsing) or HTTP client calls
(`AgentOsProxyClient`, `JiraClient`).

Minimal EP list to propose (signatures indicative; core invokes, plugin contributes/directs):
- **EP-1 `FactoryWorkflowProjectionPublisher`** (or `WorkflowProjectionContributor`)
  `fun projectWorkflow(request: ProjectionRequest): ProjectionOutcome?` default `null`/`Unsupported`.
  Called by core `WorkflowService.publishProjection` path; core still runs `WorkflowProjectionValidator.validate(...)`
  then persists + SSE-publishes (`workflow/service/WorkflowService.kt:159-200`, `projection` validated at L165).
  Forge branches here (`forge/domain/ForgeWorkflowAdapter.kt:178-179`).
- **EP-2 `FactoryRouteContributor`**
  `fun routes(): List<FactoryRoute>` default `emptyList()`, where `FactoryRoute = (method, path, handler)`.
  Core mounts them (see §4). Forge contributes `/api/forge/*`, `/api/jira/*` (and decides AgentOsProxy routes).
- **EP-3 `FactoryRunLaunchContributor`**
  `fun onWorkflowRun(request: RunLaunchRequest): RunLaunchOutcome` default no-op.
  Hook point: `WorkflowController.run` returns `"runtimeNotification": "not-configured"`
  (`workflow/web/WorkflowController.kt` `runInternal`); forge/legacy-run would plug the real launcher here.
- (Optional, only if needed) EP-4 `FactoryProjectionRequestResolver` / `FactoryNamespaceResolver`
  `resolveProjectionTarget` / `resolveRunStoreRoot` — replaces `HttpAgentOsProxyClient.resolveRunStoreRoot`.
- (Optional) EP-5 `FactoryTrustContextEnricher` — enrich `TrustContext` (e.g. namespace→repo) without core knowing forge.

**Explicit distinction to write in the doc**: ExtensionPoint = *plugin contributes, core invokes* (EP-1..EP-3).
Exported API = *plugin consumes*: `WorkflowService`, `WorkflowProjectionValidator`, `WorkflowSseHub`,
`OracleExecutionService`/publishers, `AgentStepResultService`, `ArtifactService`, error types, `TrustContext`.
For the plugin to compile against these, the *interfaces/DTOs* must live in the SDK (PF4J plugin cannot import
core `factory-service` classes — see classloading §5). So "exported API" must be re-expressed as SDK interfaces
implemented by core, injected into the plugin via an EP context or a service-locator passed at `Plugin.start()`.

Non-goals (avoid over-engineering): no EP for oracles/artifacts/delivery; forge does not currently import them.

---

## 4. Q3 — Plugin REST routes mechanism + Q4 OpenAPI

### 4.1 How AgentOS does it (the honest answer)
- **It doesn't.** Grep of `@RestController` under `agentos/*plugins*` = only `FactoryStepResultBindingController.kt`
  (a plain class, not annotated) and SDK `*Api.kt` interfaces. No plugin declares Spring MVC endpoints.
- pf4j-spring `SpringPluginManager.init()` is `@PostConstruct` → `ExtensionsInjector.injectExtensions()` calls
  `beanFactory.registerSingleton(extensionClass.name, extension)` — extensions become singleton beans (this is how
  `List<AnswerInterceptor>` gets populated). Plugin JARs are **not component-scanned**.
- Host REST control plane is `agentos/agentos-service/.../plugin/api/PluginController.kt` (`/api/plugins`).

### 4.2 Mechanism options to propose for Factory (no precedent to copy)
- **Option A — core-owned transport + plugin decision handler (closest to AgentOS).** Plugin EP returns
  *handlers* (no Spring MVC annotations), core wraps them in one `@RestController` per route family. Pros: no
  classloader/MVC-timing issues, OpenAPI stays core-generated if core declares the paths. Cons: core must know the
  route paths (couples core to forge route shapes) unless routes are data-driven.
- **Option B — plugin contributes `@RestController` classes, core registers them into MVC.** `@Extension` class is
  a Spring `@RestController`; core's `PluginConfiguration`/registrar does
  `beanFactory.registerSingleton(name, controller)` **and** calls
  `requestMappingHandlerMapping.detectHandlerMethods(bean)` (or `registerMapping`) for each, ideally from an
  `ApplicationListener<ContextRefreshedEvent>` / `SmartLifecycle` so it runs after `RequestMappingHandlerMapping`
  init. Pros: plugin owns paths (decoupled). Cons: timing fragility, and springdoc vision (next point).
- **Option C — functional routes**: core hosts a `RouterFunctionMapping`; plugin EP returns
  `RouterFunction<ServerResponse>`/`List<FactoryRoute>`; core adds them at startup. Gives path ownership + no
  annotation scanning. Likely the cleanest "plugin contributes, core invokes" fit.
- Recommended shape for the doc: **EP-2 `FactoryRouteContributor` + Option C (functional/dynamic routes)**, with
  Option A as fallback. Stat on `AgentOsProxyController` (`/api/agents`, `/api/cases/{id}/events`): it is a
  *generic AgentOS relay*, conceptually core-ish but currently forge-packaged; recommend **core** (it is a thin
  `AgentOsProxyClient` pass-through used by the cockpit, not forge domain) — or move to core together with the
  AgentOS proxy client. Decide and state it.
- Forge route families to place (`design §12.4` + controller files):
  `ForgeRunController.kt` (`/api/forge/runs…`, `/api/factory/forge/runs…`),
  `JiraProxyController.kt` (`/api/jira/{ticketId}`, `/api/factory/jira/{ticketId}`),
  `AgentOsProxyController.kt` (`/api/agents`, `/api/cases/{caseId}/events`),
  `WorkstreamController.kt` (`/api/factory/workstreams`).
- Cross-cutting helpers that must travel with the plugin: `forge/web/ForgeHttp.kt`
  (`resolveForgeCaller` uses `TenantScopeProvider`, fails closed `401 TRUST_CONTEXT_UNAVAILABLE`; `forgeError`;
  `requireNamespaceQuery`) — and note `runs/web/LegacyRun*` import these today.

### 4.3 OpenAPI contribution (Q4)
Facts: single spec file; `check-openapi-spec.sh` git-diffs it; Nx inputs are core sources + the yaml; PF4J plugin
classes are in a separate classloader not visible to springdoc.
Recommended proposal (state explicitly, with rationale against `check-openapi-spec`):
1. **Core spec = core-only.** `factory-openapi.yaml` keeps the generic surface; plugin routes are **not** in it.
   `check-openapi-spec` inputs unchanged ⇒ deterministic, plugin-presence-independent.
2. **Plugin spec = separate file**, e.g. `factory-forge-plugin/openapi/factory-forge-openapi.yaml`, produced by an
   in-module springdoc/`openapi` profile or a small test that boots with the plugin on the classpath, checked by
   its own `check-openapi-spec` Nx target (same script pattern).
3. Alternative if a merged spec is mandated: use **springdoc `group-configs` / `GroupedOpenApi`**
   (springdoc 2.8.9 supports groups; not used today) with a core group and a plugin group, and require the plugin
   JAR on the generation classpath — but this makes the core spec depend on plugin presence and must extend the Nx
   `check-openapi-spec` inputs to plugin sources. Flag as higher-risk.
4. SSE routes stay `@Operation(hidden = true)` (as today: `WorkflowSseController`, `LegacyRunSseController`).
5. `OpenApiConfig` operationId customizer already de-duplicates across controllers; keep it in core.

---

## 5. Q5 — Discovery, config, classloading (facts to copy)

- To copy verbatim from AgentOS:
  - `PluginConfiguration.kt`: `@Bean pluginManager` = `NullSafeSpringPluginManager(Path(dir)).also { it.applicationContext = ctx }`;
    creates the dir if absent.
  - `NullSafeSpringPluginManager` overrides `createExtensionFactory()` (null-safe `SpringExtensionFactory` for
    app-classpath extensions without a plugin wrapper) and `createPluginLoader()` =
    `CompoundPluginLoader().add(ApdJarPluginLoader).add(ApdDefaultPluginLoader)`, both forcing
    **`ClassLoadingStrategy.APD`**.
  - APD rationale (documented in-file): shared classes (Jackson, OkHttp, coroutines, and here **Spring/Flyway/etc.**)
    must resolve to the **service** classloader first so cross-boundary types are one class instance → no
    `LinkageError`. Plugin bundles only its own runtime deps; shared libs are `compileOnly`.
  - `PluginConfigProperties.kt`: `@ConfigurationProperties("agentos.plugins") data class(... val dir = "plugins/")`.
  - `agentos-service/src/main/resources/application.yml:210-211`: `agentos.plugins.dir: ${PLUGINS_DIR:plugins/}`;
    docker `application-docker.yml:38-39`.
- Factory proposal to state: prefix **`factory.plugins.dir`** (default e.g. `factory-service/plugins/` or `plugins/`),
  a `FactoryPluginConfigProperties` in core, `SpringPluginManager` **standalone** (factory-service is not a
  composite of agentos). Add deps: `pf4j` + `pf4j-spring` (versions 3.13.0 / 0.10.0, from agentos catalog).
- Lifecycle helper to mirror: `agentos/.../plugin/PluginService.kt` (load/unload/reload/start/stop/list). A factory
  equivalent is optional for W7.2; runtime stop/unload is how AgentOS "disables" a plugin (no allow-list —
  `.env.example` `PLUGINS_AUTO_LOAD`/`PLUGINS_DEV_MODE` are aspirational/unused).
- **Caution**: with APD the plugin's Spring/repository/JDBC classes resolve from the service CL; the plugin must
  declare Spring Data/JDBC/Jackson as `compileOnly` and never bundle them (mirror the bridge plugin's
  `compileOnly(libs.bundles.jackson)`, `compileOnly(libs.okhttp)`, `compileOnly(libs.pf4j)`).

---

## 6. Q6 — Packaging Gradle

### 6.1 AgentOS plugin blueprint (exact)
- Per-plugin: `settings.gradle.kts` (standalone, catalog from `../gradle/libs.versions.toml`,
  `includeBuild("../agentos-sdk")`), `build.gradle.kts`, `project.json` (tags `type:lib`,`platform:jvm`,`scope:plugin`).
- `build.gradle.kts`: `kotlin.jvm` + `kotlin.kapt` + `maven-publish`; deps
  `compileOnly("whoz-oss.agentos:agentos-sdk:<v>")`, `compileOnly(libs.pf4j)`, `kapt(libs.pf4j)`,
  `compileOnly(bundles.jackson)`, `compileOnly(libs.okhttp)`; `kapt { arg("pf4j.storageClassName",
  "org.pf4j.processor.LegacyExtensionStorage") }` → `META-INF/extensions.idx`; JAR bundles only own runtime deps
  (klogger) via `from(configurations.runtimeClasspath…)`, excluding `META-INF/*.SF…`, `kotlin/**`, `kotlinx/**`.
- Descriptor: **either** `src/main/resources/plugin.properties` (`plugin.id/version/provider/class/description/
  license/requires`) **or** JAR manifest attrs (`Plugin-Id/Version/Provider/Class`) — the bridge uses the manifest
  (`agentos-factory-bridge-plugin/build.gradle.kts:107-115`).
- Root `agentos/build.gradle.kts`: `val pluginBuilds = listOf(...)` + `cleanPlugins`/`jarPlugins`/`deployPlugins`
  copying `build/libs/*.jar` (excluding `*-plain.jar`) into `agentos/plugins/`; `.gitignore:44` ignores
  `plugins/*.jar`; wrapper `scripts/deploy-plugins.sh`; exposed as `coday.yaml` target `deploy-plugins`.
- **Isolation rule**: `agentos-service`/`agentos-sdk` build files contain **zero** references to plugin modules;
  the plugin list exists only in the root deploy build. Direction is strictly plugin → sdk.

### 6.2 Factory packaging proposal to state
- New module (name is the author's call; suggested `factory-forge-plugin/` at repo root, or
  `factory-service/forge-plugin/` — flag the Nx-glob consequence of each).
- Own `settings.gradle.kts` (standalone), `build.gradle.kts` with `compileOnly(factory-sdk)`, `compileOnly(pf4j)`,
  `kapt(pf4j)`, `compileOnly` Spring/Jackson/JDBC (provided by service CL), descriptor via `plugin.properties` or
  manifest, `jarPlugins`/`deployPlugins` copying into the configured plugins dir.
- **factory-service stays an independent build** (do NOT add `includeBuild(plugin)` to
  `factory-service/settings.gradle.kts` — that would make it composite). Put the plugin under a small root
  composite (e.g. top-level `factory-plugins/settings.gradle.kts` including sdk + forge plugin) so deploy tasks
  can build the JAR; or keep the plugin build standalone and copy via a script mirroring
  `scripts/deploy-plugins.sh`.
- SDK publishing: `maven-publish` to `mavenLocal` (+ optionally GitHub Packages like agentos-sdk). Plugin resolves
  it via `repositories { mavenLocal(); mavenCentral() }`. The plugin's `settings.gradle.kts` may `includeBuild`
  the SDK for local dev (agentos pattern).
- **Nx**: add `project.json` (tags `type:lib`,`platform:jvm`,`scope:plugin`) + hand-written build/test targets, and
  decide whether to generalize `tools/plugins/agentos-gradle` (currently globs `agentos/*/build.gradle.kts` only).
  Otherwise CI `nx show projects --projects=tag:platform:jvm` won't include it.

---

## 7. Q7 — Workstreams + RawSseEvent

- `WorkstreamController` (`forge/web/WorkstreamController.kt`, route `/api/factory/workstreams`),
  `WorkstreamService` (`forge/service/WorkstreamService.kt`), `JdbcWorkstreamRepository`
  (`forge/infrastructure/JdbcWorkstreamRepository.kt`) back the **generic `workstreams` table** created by
  `V2__tenant_and_membership.sql` (also `workstream_memberships` V2, `workstream_repositories` V2,
  `workstream_workflow_grants` V3; `workstream_id` is a component of every composite PK V3/V6). **No forge-named
  table exists** (forge durable state = JSONL on disk). ⇒ **Recommendation: move these three to core** (a new
  `io.whozoss.factory.workstream` package), keeping `@ConfigurationProperties`/`TenantScopeProvider` in core.
  `JdbcWorkstreamRepository : ScopedRepository<Map<String,Any?>, String>` — pure JDBC, no forge dependency.
  Tests to migrate: `factory-service/src/test/kotlin/io/whozoss/factory/forge/WorkstreamJdbcRepositoryTest.kt`.
- `RawSseEvent`: `web/RawSseEvent.kt` (core) is used by `runs/service/LegacyRunService.kt:248,271,393,400,409`
  (byte-for-byte Node SSE frames) and is conceptually generic. **Confirm: keep in core `web/`.** Note a **duplicate
  private class** `RawSseEvent` exists inside `workflow/sse/WorkflowSseHub.kt:119` — an opportunity to unify on the
  core one. Because it imports Spring (`SseEmitter`), it cannot live in a PF4J-only SDK; either exclude from SDK or
  expose a `String`-frame sink contract instead.

---

## 8. Q8 — Plan W7.2 / W7.3 (inputs, verification, pitfalls)

### 8.1 Sequence facts to anchor the plan
- Current couplings to break (design §3): implicit component scan; `application.yml factory.forge.*`; single
  generated OpenAPI; `runs↔forge` helper imports; forge config tree.
- Nothing in core imports forge ⇒ extraction is mostly **move + wire**, not refactor.
- W7.2 = infra plugin (PF4J, SDK, deploy) with **forge still in core**; W7.3 = move forge/runs into the plugin.
- Verification criteria to state:
  - **Core without JAR** boots (start `factory-service` with an empty plugins dir; no forge beans, no forge paths
    in the core spec).
  - **Core with JAR** = W6a parity: same forge routes/paths, same JSONL behaviour, same error envelopes, same
    OpenAPI forge surface (from the plugin spec), tests green.
  - `check-openapi-spec` still passes (core-only spec unchanged by plugin presence).
  - `pnpm nx affected -t test` (the factory suite) green; JVM `pnpm nx test factory-service`; plugin module
    `build/test` green.
- Pitfalls to call out:
  1. **Classloading/`LinkageError`** — must use APD; plugin must not bundle Jackson/Spring/coroutines; SDK types
     crossing the boundary must resolve to the service CL.
  2. **OpenAPI/CI** — never let the core spec depend on plugin presence; update Nx inputs/glob if the plugin must
     be in `tag:platform:jvm`.
  3. **`@SpringBootApplication` implicit scan** — with forge moved out, core keeps scanning only its packages;
     plugin `@Extension` beans are NOT component-scanned (pf4j-spring registers them as singletons) — verify all
     forge `@Service/@Repository/@Configuration/@RestController` get instantiated via the plugin mechanism.
  4. **runs↔forge helpers** — `LegacyRunController`/`LegacyRunSseController` import forge.web helpers and
     `ForgeConfiguration` wires `LegacyRunService`; decide runs' home before moving forge.
  5. **Sandbox false-green** — the doc must require a **full clean local run** (`./gradlew clean test`, not just
     affected) because the affected graph may not cover the new Gradle composite/plugin module.
  6. **Descriptor/idx** — kapt `extensions.idx` + plugin.properties/manifest must be correct or `getExtensions`
     returns nothing (silent no-op → looks green).
  7. **Config prefix move** — `factory.forge.*` → plugin-owned prefix; `ForgeProperties` must move with the plugin
     (or be replaced by plugin-local config read from sysprops/env like the bridge's `FactoryBridgeConfig`).
  8. **H2 openapi profile** — plugin controllers must not require Postgres at generation time (mirror
     `application-openapi.yml`).

### 8.2 Extra facts useful for the plan
- Sizes: `forge/**` = 4295 LOC; `runs/**` = 676 LOC; forge+runs tests = 1170 LOC
  (`factory-service/src/test/kotlin/io/whozoss/factory/{forge,runs}`). Test base `DomainIntegrationTest`
  (Postgres testcontainers) seeds tenant rows; forge tests: `ForgeGatesIntegrationTest`, `ForgeLedgerIntegrationTest`,
  `ForgeStoryOperationsTest`, `ForgeDomainUnitTest`, `WorkstreamJdbcRepositoryTest`, `JiraProxyHttpTest`,
  `AgentOsProxyMockTest`, `LegacyRunAndSseHttpTest`.
- `runs/service/LegacyRunService.kt` spawns `node <runEntry> <workflow>` (`factory/run.mjs` default at
  `ForgeProperties.kt:18`) — the W6b bridge to the Node instrument B; it must not be deleted in W7 (see
  `docs/factory-node-cut-inventory.md` §4.3/§6.1.4). Decide core vs plugin for `runs/**`.
- `forge-epic.mjs` is orphaned (no production caller; created in-process by Node dashboard and Kotlin
  `ForgeRunService`) — a retirement candidate, not a W7 gate.
- `forge_bmad/coday/**` is a Coday project overlay (agents/prompts/skills/scripts calling `:3141`) — the "domain
  plugin" will likely ship this overlay in addition to the Kotlin JAR.
- Product/agent consumers of the Factory API on `:3141` that must be repointed to `:8141` (cut-inventory §5.6):
  `apps/client/proxy.conf.json:11-15`, `libs/integration/src/lib/factory.tools.ts` (+schemas/types/validation/tests),
  `libs/model/src/lib/project-description.ts:8-17`,
  `agentos/agentos-factory-bridge-plugin/.../FactoryBridgeConfig.kt:31,39-43`,
  `forge_bmad/coday/**` + `PROJECT_SCRIPTS.yaml`.

---

## 9. Reference docs / specs (paths)
- `docs/factory-generic-core-forge-plugin-design.md` (697 lines, committed `46c64db7`) — the 13-section design
  input (§2.2/§12.1 consumed surface, §1/§13 AgentOS pattern, §3/§5 couplings, §8 open questions).
- `docs/factory-node-cut-inventory.md` (W6b inventory; §4.2 forge-epic, §5.6 consumers, §6.2 sequence).
- `app_docs/453164ec_forge-bmad-runs.md` (W6a), `specs/453164ec_forge_bmad_runs_kotlin_port.md` (A8 port).
- `specs/6786691c_factory_service_socle.md` (W0 socle), `specs/1723c7a4_spi_hooks_foundation.md` (T2a SPI hooks),
  `specs/90527113_extract_factory_bridge_plugin.md` + `specs/f5e09c98_...` (T2b extraction blueprint),
  `specs/2faf6299_cleanup_factory_bridge_residues.md` (T2c cleanup — proves no host REST route was kept).
- `factory/WORKFLOW_PROJECTION.md` (generic projection contract + domain-declaration model),
  `factory/FORGE_WORKFLOW_ADAPTER.md` (Forge→generic pipeline).
- `coday.yaml:99-106` — only a `deploy-plugins` target today; no factory plugin concept.

---

## 10. Deliverable reminder
- Target file: `docs/factory-forge-plugin-spi.md`, sections §1 Résumé & décisions … §8 Plan W7.2/W7.3 (see prompt).
- Commit ONLY that file, message `docs: add factory forge-plugin SPI design (W7.1)`, then report the hash.
- No code changes; this scout file is the handoff under `context_handoff/`.
