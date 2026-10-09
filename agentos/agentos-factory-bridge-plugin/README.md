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
| `ToolPlugin` | `FactoryWorkstreamToolPlugin` (`FACTORY_WORKSTREAM` integration: 8 tools — the six read-only `FACTORY_WORKSTREAM__*` views plus the boundary request commands `FACTORY_WORKSTREAM__start_workflow` and governed `FACTORY_WORKSTREAM__request_agent_retry`) |
| `ToolPlugin` | `FactoryWorkerToolPlugin` (`FACTORY_WORKER` integration: terminal `FACTORY_WORKER__submit_step_result` only; human questions use AgentOS standard `queryUser`) |
| `AnswerInterceptor` | `FactoryAnswerInterceptor` (handles only independent Factory business checkpoints; standard `queryUser` answers remain owned and persisted by AgentOS) |
| `CaseLifecycleObserver` | `FactoryCaseLifecycleObserver` (invalidates a binding + checkpoint on a terminal case) |
| `ToolGrantPolicy` | `FactoryToolGrantPolicy` (gates the `FACTORY_WORKER__*` tools on an active binding, fail-closed) |
| `ExternalContextBindingRegistrar` | `FactoryBindingRegistrar` (host transport → durable step-result binding) |

Both `ToolPlugin` extensions live in this single JAR and share every bridge service
(HTTP client, bindings, config, SSE listeners, state store) through
`FactoryBridgePluginHolder`. Both declare an empty-object `configSchema`, so they appear
in the standard integration catalog and resolve through the ordinary
`ToolResolverService` flow. The Workstream boundary also carries two boundary request
commands while authority stays with the Factory: `FACTORY_WORKSTREAM__start_workflow`
creates an authoritative governed workflow, and `FACTORY_WORKSTREAM__request_agent_retry`
only creates a `pending-human` retry request under a revision fence — unblocking or
skipping steps remains human-only in the Factory cockpit. The retired config-less
`FACTORY` plugin and its migration path are documented in
[docs/factory-trust-boundary-migration.md](docs/factory-trust-boundary-migration.md).

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
| Encryption key | `agentos.encryption.key` | `AGENTOS_ENCRYPTION_KEY` | *(required — see below)* |
| Encryption salt | `agentos.encryption.salt` | `AGENTOS_ENCRYPTION_SALT` | *(required — see below)* |

An empty shared secret **disables** the binding endpoint (fail-closed).

The last two are **the host's own encryption settings**, not plugin-specific ones: the
bridge reads the same `AGENTOS_ENCRYPTION_*` pair that `FieldEncryptor` uses service-side,
so a deployment configures encryption once. The resolution is identical to the host's
`FieldEncryptorConfiguration`:

- both set to real values → AES-256-GCM on the capability token at rest;
- both set to `NONE` (case-insensitive) → plaintext, explicitly opted out, WARN logged;
- anything else → the plugin **fails to start**.

There is no silent fallback to plaintext. A half-configured pair is a startup failure
rather than a quiet downgrade — the dangerous outcome would be storing secrets in the
clear while believing they are encrypted. A service already configured for encryption
needs no extra setting for the bridge; one running with `NONE` keeps working unchanged.

## Durable state (restart-safe)

`FactoryBridgeStateStore` mirrors, in `<data-dir>/bridge-state.json`, using an atomic
*write-temp-then-rename* with owner-only permissions (`600` in a `700` directory — the
temp file is created restricted **before** being written, since `ATOMIC_MOVE` replaces the
destination inode and only the temp file's permissions survive):

- **step-result bindings** — `FactoryStepResultBindingRegistry` writes through on every
  `bind` / `acquire` (single-flight CAS lease) / `release` / `acknowledge` / `invalidate`
  / expiry / removal. An unfinished binding and its lease survive an AgentOS restart.
  A corrupt/absent file starts empty, which is **fail-closed** (no capability ⇒ no
  submission).
- **pending human checkpoints** — `FactoryBridgeServices.pendingCheckpoints` is backed by
  the same file, so an approval opened before a restart is still answerable after it.

### The capability token at rest

The token is a bearer credential: whoever reads it can submit a step result for that
attempt until it expires. Three measures apply, in depth:

1. **Encrypted** with AES-256-GCM (random IV per write, so the same token yields a
   different ciphertext each time) under the host's encryption key.
2. **Owner-only** on disk, as described above.
3. **Purged** once expired for more than 24h (`EXPIRED_RETENTION`). Expiry alone does not
   evict — a lapsed capability must stay renewable through
   `FactoryStepResultCapabilityRefresher`, which takes the stored binding as its input —
   but keeping it *forever* would leave a secret on disk long after any legitimate use.
   An abandoned case would otherwise retain one indefinitely.

A token that fails to decrypt (rotated key, file written while encryption was disabled) is
**dropped, never resurrected**: no capability means no submission, and the Factory can
reissue one. The reverse — half-reading a binding — is the hazardous direction.

`FactorySseHighWaterMarkStore` holds no secret but still exposes case and attempt ids, so
it is written with the same owner-only guarantees.

`FactorySseHighWaterMarkStore` persists the bridge-side observation cursor in
`<data-dir>/sse-high-water-marks.json`: one `(timestamp, lastEventId)` high-water mark per
`(caseId, attemptId)`. The observation protocol replays the full stream and deduplicates by
`eventId`; the persisted cursor avoids re-processing already-handled events after a restart.
The mark only ever moves forward.

Both stores are created by `FactoryBridgeServices.create(config)` and live in the plugin
classloader; nothing is added to the SDK or the host.

## Host transport (how a binding reaches the registry)

The AgentOS host owns the HTTP transport, the plugin owns the trust decision:

1. The Factory creates a case with the `X-External-Context-Attempt-Id` and
   `X-External-Context-Capability-Token` headers (optionally `X-External-Context-Runtime-Id`,
   `X-External-Context-Agent-Name`, `X-External-Context-Expires-At`) and the shared secret.
2. `io.whozoss.agentos.binding.ExternalContextBindingFilter` intercepts `POST /api/cases`,
   reads the created case id/namespace from the response, and forwards the opaque
   attributes to every `ExternalContextBindingRegistrar` found via PF4J.
3. Alternatively, bind explicitly with
   `PUT /internal/external-context/cases/{caseId}/bindings` and the same headers.
4. `FactoryBindingRegistrar` validates the shared secret (constant-time), resolves the
   binding facts and records a durable binding. `FACTORY_WORKER__submit_step_result` then
   redeems it through `FactoryStepResultBindingRegistry.acquire`, which hands out a
   single-flight lease — the token never passes through the model.

   > The capability deliberately travels through the registry rather than through any
   > per-turn context value: an inert value resolved once at the start of a turn cannot
   > express a lease, a renewal or an acknowledgement. See
   > [docs/factory-trust-boundary-migration.md](docs/factory-trust-boundary-migration.md).

If the Factory does not send an agent name, the binding is stored with the wildcard agent
`*`, so the case agent can still redeem the capability (`FACTORY_WORKER__submit_step_result`).

## Tests

The plugin is an included (composite) build with no Gradle wrapper of its own, so it is
neither `:agentos-factory-bridge-plugin:test` from the root build nor `./gradlew test`
from this directory. Its `test` target is inferred from `project.json` +
`build.gradle.kts` by the local Nx plugin (`tools/plugins/agentos-gradle/`):

```bash
pnpm nx test agentos-factory-bridge-plugin
```

Covers: durable binding + lease across a simulated restart, single-flight CAS after
restart, terminal invalidation durability, pending-checkpoint durability, SSE high-water
mark persistence/deduplication, the host-transport registrar wiring, and the at-rest
security properties (no plaintext token on disk, owner-only permissions surviving a
rewrite, undecryptable tokens dropped, expired-binding purge, fail-fast on a
half-configured encryption pair).
