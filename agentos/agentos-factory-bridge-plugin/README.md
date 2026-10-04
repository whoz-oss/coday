# agentos-factory-bridge-plugin

Standalone PF4J plugin that carries the **Factory ↔ AgentOS bridge** outside of AgentOS
core. It exposes the Factory tools and the bridge SPI extensions, and — as of the durable
bridge work — keeps its bindings, leases, pending human checkpoints and SSE high-water
marks **restart-safe**.

> Nothing here is specific to the TS/Nx monorepo: the plugin is a Gradle composite build
> under `agentos/`, packaged as a PF4J JAR and loaded by `agentos-service`.

## What it contributes

| Extension point (`agentos-sdk`) | Implementation |
|---|---|
| `ToolPlugin` | `FactoryWorkstreamToolPlugin` (`FACTORY_WORKSTREAM` integration: the six read-only Workstream tools `FACTORY_WORKSTREAM__*`) |
| `ToolPlugin` | `FactoryWorkerToolPlugin` (`FACTORY_WORKER` integration: `FACTORY_WORKER__submit_step_result`, `FACTORY_WORKER__ask_step_question`) |
| `AnswerInterceptor` | `FactoryAnswerInterceptor` (submits the user decision to the Factory checkpoint) |
| `CaseLifecycleObserver` | `FactoryCaseLifecycleObserver` (invalidates a binding + checkpoint on a terminal case) |
| `ExternalExecutionContextProvider` | `FactoryExternalExecutionContextProvider` (injects `capabilityToken` / `attemptId` / `runtimeId`) |
| `ToolGrantPolicy` | `FactoryToolGrantPolicy` (gates the `FACTORY_WORKER__*` tools on an active binding, fail-closed) |
| `ExternalContextBindingRegistrar` | `FactoryBindingRegistrar` (host transport → durable step-result binding) |

Both `ToolPlugin` extensions live in this single JAR and share every bridge service
(HTTP client, bindings, config, SSE listeners, state store) through
`FactoryBridgePluginHolder`. Both declare an empty-object `configSchema`, so they appear
in the standard integration catalog and resolve through the ordinary
`ToolResolverService` flow. The retired config-less `FACTORY` plugin and its migration
path are documented in [docs/factory-trust-boundary-migration.md](docs/factory-trust-boundary-migration.md).

## Packaging & load

The plugin is declared in the root build (`agentos/build.gradle.kts`):

- `pluginBuilds` contains `agentos-factory-bridge-plugin`;
- `deployPlugins` builds every plugin (after `cleanPlugins`) and copies its
  `build/libs/*.jar` (excluding `*-plain.jar`) into `agentos/plugins/`.

```bash
cd agentos
./gradlew deployPlugins          # builds + copies every plugin JAR into agentos/plugins/
./gradlew :agentos-service:bootRun   # bootRun's working dir is agentos/, so plugins/ resolves
```

`agentos-service` discovers the JAR at startup through its `SpringPluginManager` bean
(`io.whozoss.agentos.config.PluginConfiguration`), whose `init()` is a `@PostConstruct`
that loads and starts plugins and registers every extension as a Spring bean. If the JAR is
missing from `plugins/` the bridge is silently absent — `GET /api/plugins` lists what was
actually loaded.

> `agentos/plugins/` is git-ignored: the JARs are build artifacts, never committed.

To load it manually: copy `agentos-factory-bridge-plugin/build/libs/agentos-factory-bridge-plugin-*.jar`
into `agentos/plugins/` and restart the service (or use `POST /api/plugins/upload`).

## Configuration

All values are read from JVM system properties or environment variables (the plugin runs
inside the host process and does not use Spring `@Value`):

| Value | System property | Environment variable | Default |
|---|---|---|---|
| Factory base URL | `agentos.factory.base-url` | `AGENTOS_FACTORY_BASE_URL` | `http://localhost:8141` |
| Runtime id | `agentos.factory.runtime-id` | `AGENTOS_FACTORY_RUNTIME_ID` | `agentos-primary` |
| Durable data dir | `agentos.factory-bridge.data-dir` | `AGENTOS_FACTORY_BRIDGE_DATA_DIR` | `data/factory-bridge` |
| Shared secret | `agentos.factory-bridge.secret` | `AGENTOS_FACTORY_BRIDGE_SECRET` | *(empty → binding disabled)* |
| Binding TTL (s) | `agentos.factory-bridge.binding-ttl` | `AGENTOS_FACTORY_BRIDGE_BINDING_TTL` | `3600` |

An empty shared secret **disables** the binding endpoint (fail-closed).

## Durable state (restart-safe)

`FactoryBridgeStateStore` mirrors, in `<data-dir>/bridge-state.json`, using an atomic
*write-temp-then-rename*:

- **step-result bindings** — `FactoryStepResultBindingRegistry` writes through on every
  `bind` / `acquire` (single-flight CAS lease) / `release` / `acknowledge` / `invalidate`
  / expiry / removal. An unfinished binding and its lease survive an AgentOS restart.
  A corrupt/absent file starts empty, which is **fail-closed** (no capability ⇒ no
  submission).
- **pending human checkpoints** — `FactoryBridgeServices.pendingCheckpoints` is backed by
  the same file, so an approval opened before a restart is still answerable after it.

`FactorySseHighWaterMarkStore` persists the bridge-side observation cursor in
`<data-dir>/sse-high-water-marks.json`: one `(timestamp, lastEventId)` high-water mark per
`(caseId, attemptId)`. The observation protocol replays the full stream and deduplicates by
`eventId`; the persisted cursor avoids re-processing already-handled events after a restart.
The mark only ever moves forward.

Both stores are created by `FactoryBridgeServices.create(config)` and live in the plugin
classloader; nothing is added to the SDK or the host.

## Host transport (how a binding reaches the registry)

The AgentOS host owns the HTTP transport, the plugin owns the trust decision:

1. The Factory creates a case with the `X-Factory-Attempt-Id` and
   `X-Factory-Capability-Token` headers (optionally `X-Factory-Runtime-Id`,
   `X-Factory-Agent-Name`, `X-Factory-Expires-At`) and the shared secret.
2. `io.whozoss.agentos.binding.ExternalContextBindingFilter` intercepts `POST /api/cases`,
   reads the created case id/namespace from the response, and forwards the opaque
   attributes to every `ExternalContextBindingRegistrar` found via PF4J.
3. Alternatively, bind explicitly with
   `PUT /internal/external-context/cases/{caseId}/bindings` (alias
   `PUT /internal/factory/cases/{caseId}/step-result-binding`) and the same headers.
4. `FactoryBindingRegistrar` validates the shared secret (constant-time), resolves the
   binding facts and records a durable binding. `FactoryExternalExecutionContextProvider`
   then exposes `capabilityToken` / `attemptId` / `runtimeId` to the case run instead of an
   empty `{}`.

If the Factory does not send an agent name, the binding is stored with the wildcard agent
`*`, so the case agent can still redeem the capability (`FACTORY_WORKER__submit_step_result`).

## Tests

```bash
cd agentos
./gradlew :agentos-factory-bridge-plugin:test
```

Covers: durable binding + lease across a simulated restart, single-flight CAS after
restart, terminal invalidation durability, pending-checkpoint durability, SSE high-water
mark persistence/deduplication, and the host-transport registrar wiring.
