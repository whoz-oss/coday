# Plan — AgentOS Execution Adapter boundary & robust SSE Client (factory-service)

## Goal

Introduce an explicit, typed AgentOS **Execution Adapter** boundary inside `factory-service`,
backed by a robust SSE client that reconciles against REST, **without disabling the existing
polling** in `HttpAgentOsProxyClient` and **without modifying the AgentOS core**.

The adapter derives strict, evidence-backed verdicts (`Succeeded` / `WaitingHuman` / `Failed` /
`Interrupted` / `Indeterminate`) and **never treats silence or `RUNNING` as success**.

This is additive: new package `io.whozoss.factory.adapter.agentos`, new types, new client, new
tests. Existing code (`proxy/HttpAgentOsProxyClient.kt`, `capability/*`, `SessionRunService`) stays
functionally unchanged; at most an **optional, flagged** wiring bean is added.

## Context you must read first

- `app_docs/agentos-sse-contract.md` — the frozen SSE contract. Key facts the client must honour:
  - Endpoint: `GET /api/cases/{caseId}/events?includePreviousEvents=true` (single channel
    `event: case-event`, `id:` = `CaseEvent.id` UUID, `data:` = JSON `CaseEvent` discriminated by `type`).
  - **No cursor / no `Last-Event-ID`**: every (re)connection replays the full durable history.
    Deduplicate client-side by `eventId`.
  - Transient events (`ThinkingEvent`, `TextChunkEvent`, `CaseUpdatedEvent`) are never persisted and
    never replayed → display-only, never used for verdicts.
  - Ordering is `timestamp ASC, id ASC`. A `(timestamp, lastEventId)` high-water mark is a valid
    incremental checkpoint, but always fall back to full replay + eventId dedup on a fresh start.
  - On saturation/disconnect the server closes with an error to force reconnect+replay — treat a
    dropped connection as "replay from the start and dedup", never as a gap.
  - REST reconciliation source: `GET /api/case-events/by-parentId/{caseId}` (same durable, ordered
    events); `GET /api/cases/{caseId}` gives a cheap `status` liveness read.
  - Heartbeat: `:keep-alive` comment frames (~30 s); a stalled stream = missing heartbeats.
  - `POST /api/cases/{caseId}/kill` for the kill path.
- `app_docs/3d9fc3b1_durable-factory-bridge.md` — the AgentOS-side durable bridge (binding registry,
  `FactorySseHighWaterMarkStore` on the plugin side keyed by `(caseId, attemptId)`). Our Factory-side
  checkpoint mirrors that `(timestamp, lastEventId)` shape. Note this is the **AgentOS plugin** side;
  our new code lives in **factory-service** and does not touch it.
- Existing source to model on / not break:
  - `factory-service/src/main/kotlin/io/whozoss/factory/proxy/HttpAgentOsProxyClient.kt` — the current
    polling turn driver. **Keep it.** Reuse its verdict-derivation vocabulary (status handling,
    `lastUnansweredQuestion`, `lastAgentMessage`, `turnFacts`) as the reference for the new typed rules.
  - `factory-service/src/main/kotlin/io/whozoss/factory/proxy/AgentOsProxyClient.kt` — the proxy
    interface + `AgentTurnExecutionResult` sealed type (do not change its contract).
  - `factory-service/src/main/kotlin/io/whozoss/factory/proxy/ProxyProperties.kt` /
    `ProxyConfiguration.kt` — config + bean wiring pattern.
  - `factory-service/src/main/kotlin/io/whozoss/factory/capability/AgentOsAgentTurnCapability.kt` and
    `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt` — the
    consumers. **Do not move their polling logic.**
  - `factory-service/src/test/kotlin/io/whozoss/factory/proxy/AgentOsAgentTurnTest.kt` — the test
    style to mirror (JUnit 5 + AssertJ + `MockRestServiceServer`).

## Constraints (hard requirements)

1. **Do NOT disable or alter** the polling in `HttpAgentOsProxyClient`. It stays the active
   turn driver.
2. **Do NOT modify** the AgentOS core (`agentos/…/caseEvent`, `agentos/…/caseFlow`) or the bridge plugin.
3. Boundary stays **HTTP only** — never import an AgentOS Kotlin type. Model `CaseEvent` shapes as
   local Kotlin data classes / a local sealed hierarchy fed from JSON `Map<String,Any?>` (mirroring how
   `HttpAgentOsProxyClient` already reads `Map<String,Any?>` event lists).
4. Idempotency at the Factory boundary is keyed by `attemptId` (the checkpoint / high-water mark is
   keyed by `(caseId, attemptId)`, matching the bridge doc).
5. `RUNNING` proves only "started", never success. `KILLED` / timeout / `ERROR` must **never** become
   `Succeeded`. A verdict is **never** derived from silence — an exhausted reconnection budget →
   `Indeterminate`.
6. No new runtime dependency unless justified. Use the JDK's built-in `java.net.http.HttpClient`
   (JDK 25) for SSE streaming and `com.sun.net.httpserver.HttpServer` for the test double — both are
   on the classpath already, so **no `build.gradle.kts` change is required**. Only add a dependency if
   a gap is proven; if so, document it in the plan follow-up and prefer test-scoped.

## Files to create

All under `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/` unless noted.

### 1. `AgentOsExecutionVerdict.kt` — typed result vocabulary

Sealed interface `AgentOsExecutionVerdict`, standalone (no dependency on `AgentTurnExecutionResult`
or any attempt model):

```kotlin
sealed interface AgentOsExecutionVerdict {
    val evidence: Map<String, Any?>

    data class Succeeded(
        val outputs: Map<String, Any?>,
        override val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    data class WaitingHuman(
        val questionRef: String,
        val questionText: String? = null,
        override val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    data class Failed(
        val code: String,
        val message: String,
        override val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    data class Interrupted(
        val reason: String,
        override val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict

    data class Indeterminate(
        val reason: String,
        override val evidence: Map<String, Any?> = emptyMap(),
    ) : AgentOsExecutionVerdict
}
```

(`evidence` on the base interface is optional polish; if it complicates the `data class`
signatures required by the prompt, keep `evidence` per-subtype exactly as the prompt lists — the
prompt's shapes are authoritative. Match the prompt's field names and defaults.)

### 2. `CaseEventView.kt` — local, HTTP-boundary event model

- A small immutable view over a durable AgentOS event decoded from JSON `Map<String,Any?>`:
  `eventId: String` (`id`), `type: String`, `caseId: String?`, `timestamp: String?`, and typed
  accessors used by verdict rules (`status` for `CaseStatusEvent`, `questionId`/`question` for
  `QuestionEvent`, `questionId` for `AnswerEvent`, agent `MessageEvent` content extraction).
- A `TransientCaseEventTypes` set = `{"ThinkingEvent","TextChunkEvent","CaseUpdatedEvent"}` and an
  `isTransient()` helper. Transient events are dropped before any verdict logic (they should not
  appear over durable REST or replay anyway, but the SSE live stream can carry them).
- Factory function `CaseEventView.fromJson(map)` returning null when `id` is missing/blank.
- Reuse the parsing helpers already proven in `HttpAgentOsProxyClient` (message content flattening,
  `lastUnansweredQuestion` logic) — lift them into pure functions here so both the polling client and
  the adapter share one source of truth (extract to a small internal `CaseEventReasoning` object;
  `HttpAgentOsProxyClient` may optionally delegate to it, but that refactor is **optional** and must
  not change its behaviour or its tests).

### 3. `HighWaterMark.kt` — restart-safe checkpoint (in-memory, adapter-scoped)

- Data class `HighWaterMark(timestamp: String?, lastEventId: String?)` keyed by `(caseId, attemptId)`.
- `covers(event)` / `isDuplicate(eventId)` semantics mirroring the bridge doc's
  `FactorySseHighWaterMarkStore` (monotone advance; full replay + eventId dedup on fresh start).
- Backed by a bounded `seen` id set per `(caseId, attemptId)` so an unbounded case history does not
  grow the set without limit (rolling window on `(timestamp,id)`; document the window size as a const).
- Phase-1 store is a process-local `ConcurrentHashMap` (durable persistence is explicitly out of scope
  for this ticket — the AgentOS-side store already exists; a Factory-side durable store is a follow-up).
  State the volatility in the KDoc: loss of the mark ⇒ treated as a fresh start (full replay + dedup),
  never a verdict by silence.

### 4. `AgentOsSseClient.kt` — the robust SSE observer

Responsibilities:

- Open `GET /api/cases/{caseId}/events?includePreviousEvents=true` with the trusted
  `X-External-User-Id` header (and the `X-Factory-*` capability headers when supplied, mirroring
  `HttpAgentOsProxyClient.createCase`/`postMessage`).
- Use `java.net.http.HttpClient` with a streaming body handler; parse the SSE wire format line by
  line into frames (`id:`, `event:`, `data:`, blank-line dispatch, `:comment` heartbeats ignored but
  used as liveness). Build a small internal `SseFrameParser` (pure, unit-testable).
- **Strict caseId filtering:** decode each `data:` frame to `CaseEventView`; ignore any event where
  `caseId != targetCaseId` (defensive — the endpoint is per-case but the adapter must not trust it).
- **Client-side dedup by `eventId`** via the `HighWaterMark`/seen-set; each event processed at most once
  across replay + live + reconnect.
- Drop transient events (`isTransient()`) — they never feed verdict/checkpoint logic.
- **Reconnection with bounded exponential backoff** (configurable base/max/attempts, e.g. base 500 ms,
  cap 30 s, jitter). On **every** reconnection or connection failure, first run `reconcile(caseId)`
  (REST catch-up) before/after re-opening the stream so no durable event is missed.
- **Heartbeat-stall detection:** if no frame or `:keep-alive` arrives within a stall timeout (e.g.
  2× the 30 s heartbeat), treat as a dropped connection → reconnect+reconcile.
- Overall observation timeout (`timeoutMs`): when the budget/retries are exhausted, transition to
  `Indeterminate("SSE reconnection budget exhausted")` (or `Indeterminate("SSE observation timeout")`
  when the wall-clock budget elapses). **Never** infer success/failure from silence.
- Expose a method returning the derived `AgentOsExecutionVerdict` once a terminal/quiescent state is
  observed (delegating verdict derivation to `VerdictDeriver`, item 6).
- Fully injectable for tests: `HttpClient` (or a stream-supplier lambda), clock (`now: () -> Long`),
  `sleep`/scheduler, backoff params — following the constructor-injection style of
  `HttpAgentOsProxyClient` (`sleep`, `now` seams).

### 5. `AgentOsExecutionAdapter.kt` — interface + `DefaultAgentOsExecutionAdapter.kt`

Interface operations (typed, HTTP-boundary):

```kotlin
interface AgentOsExecutionAdapter {
    fun createOrRecoverExecution(
        namespaceId: String, workflowId: String, stepId: String,
        externalUserId: String?, attemptId: String, capabilityToken: String?, caseId: String,
    ): CaseHandle                      // idempotent by attemptId: create if absent, recover otherwise

    fun startTurn(
        caseId: String, persona: String, brief: String,
        externalUserId: String?, attemptId: String, capabilityToken: String?,
    )

    fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict

    fun reconcile(caseId: String): AgentOsExecutionVerdict   // REST-only catch-up + verdict derivation

    fun interrupt(caseId: String, reason: String)            // request stop/kill

    fun kill(caseId: String)                                 // POST /api/cases/{caseId}/kill
}
```

- `DefaultAgentOsExecutionAdapter` is a Spring `@Component` (or a `@Bean` in a new
  `AgentOsAdapterConfiguration`) that:
  - `createOrRecoverExecution`: `POST /api/cases` with `id=caseId`, `namespaceId`, `attemptId`,
    `capabilityToken` and the `X-Factory-*` headers (same body/headers as
    `HttpAgentOsProxyClient.createCase`). **Idempotent by `attemptId`**: keep a boundary record keyed by
    `attemptId`; a repeated call with the same `attemptId` recovers the existing case (via
    `GET /api/cases/{caseId}` / event replay) instead of re-driving it.
  - `startTurn`: `POST /api/cases/{caseId}/messages` with `@persona brief` (mirror
    `HttpAgentOsProxyClient.postMessage`, incl. the `X-Factory-*` headers). Guard against a busy case
    (non-quiescent status) exactly like the existing `AGENT_CASE_BUSY` check.
  - `observeTurn`: drive `AgentOsSseClient` and return its typed verdict.
  - `reconcile`: pull `GET /api/case-events/by-parentId/{caseId}` (and `GET /api/cases/{caseId}` for
    status), run `VerdictDeriver` over the durable events, advance the high-water mark, return the
    verdict. Used both standalone and by the SSE client on every reconnection.
  - `interrupt`: best-effort stop — `POST /api/cases/{caseId}/kill` (there is no distinct stop route in
    the contract), returning without throwing; the caller decides the verdict. Document that a
    kill-triggered terminal state derives to `Interrupted`/`Failed`, never `Succeeded`.
  - `kill`: `POST /api/cases/{caseId}/kill` (quiet, best-effort, mirrors `killQuietly`).
- Add a `CaseHandle` small data class (`caseId`, `namespaceId?`, `recovered: Boolean`).
- Reuse `RestClient` for the REST calls (same builder/`baseUrl` pattern as the proxy config);
  use `java.net.http.HttpClient` only for the streaming SSE call.

### 6. `VerdictDeriver.kt` — the strict verdict rules (pure, shared)

A pure object/function `derive(events: List<CaseEventView>, terminalStatus: String?, context): AgentOsExecutionVerdict`
enforcing, in order:

- `RUNNING` seen but no terminal/quiescent status → **not terminal**; caller keeps observing (never a
  verdict). Encoded as a nullable/"not yet" result or a dedicated `null` return the SSE client loops on.
- On `IDLE`:
  - Unanswered `QuestionEvent` (a `QuestionEvent.id` with no matching `AnswerEvent.questionId`) →
    `WaitingHuman(questionRef = questionId, questionText = question, evidence = facts)`.
  - Else a valid structured result present (define "structured result" precisely — see below) →
    `Succeeded(outputs, evidence)`.
  - Else (IDLE, no question, no structured output) →
    `Indeterminate("Turn reached IDLE without structured output")`.
- `ERROR` → `Failed("AGENT_CASE_ERROR", …, evidence)`.
- `KILLED` → `Failed("AGENT_CASE_KILLED", …, evidence)` (or `Interrupted` when the kill was caller-
  initiated via `interrupt`; pass that intent through `context`). **Never `Succeeded`.**
- Observation timeout (no terminal state within budget) → `Indeterminate("SSE observation timeout")`.
- Reconnection budget exhausted → `Indeterminate("SSE reconnection budget exhausted")`.
- `evidence` map = the `turnFacts` shape already produced by `HttpAgentOsProxyClient`
  (`caseId`, `caseStatus`, `agentTurns`, `toolCalls`, plus `summary`/`question` where relevant).

**Define "structured result" for `Succeeded`:** phase-1, mirror the existing polling client, which
treats an `IDLE` with no pending question as success and carries the last agent `MessageEvent` as the
`summary`. So `outputs = { "summary": <last agent message> }` and success requires **at least a
terminal `IDLE` plus a non-null last agent message OR an explicit structured result event**. If the
turn reaches `IDLE` with neither a question nor any agent message/structured output, return
`Indeterminate` (this is the stricter behaviour the prompt asks for, diverging deliberately from the
polling client's "empty summary = success"). Document this divergence in the KDoc — it is the point of
the adapter: no success by silence.

### 7. Config & wiring

- Add `AgentOsAdapterProperties` (`@ConfigurationProperties(prefix = "factory.adapter.agentos")`):
  `enabled: Boolean = false`, backoff params (`backoffBaseMs`, `backoffMaxMs`, `maxReconnects`),
  `stallTimeoutMs`, `observationTimeoutMs`. Reuse `factory.proxy.agentosUrl` for the base URL (do not
  duplicate it).
- Add `AgentOsAdapterConfiguration` with the `DefaultAgentOsExecutionAdapter` bean **guarded by
  `@ConditionalOnProperty("factory.adapter.agentos.enabled", havingValue = "true")`** so it is inert by
  default and cannot interfere with the live polling path.
- **`SessionRunService`:** do **not** wire the adapter into the run loop in this ticket. If any wiring
  is desired, restrict it to an optional, flag-guarded constructor param that is **null by default and
  unused when null** — no existing polling logic moves. State clearly in the plan that the default
  build keeps `AgentOsAgentTurnCapability` → `HttpAgentOsProxyClient` polling untouched. (Recommended:
  add no `SessionRunService` change at all; ship the adapter as a self-contained, tested boundary.)

## Tests to create

Under `factory-service/src/test/kotlin/io/whozoss/factory/adapter/agentos/`. Mirror
`AgentOsAgentTurnTest` style (JUnit 5 + AssertJ). Two kinds of doubles:

- **REST doubles** (`reconcile`, `createOrRecoverExecution`, `startTurn`, `kill`): use
  `MockRestServiceServer` bound to the `RestClient.Builder`, exactly as `AgentOsAgentTurnTest` does.
- **SSE doubles:** stand up a tiny in-process SSE server with `com.sun.net.httpserver.HttpServer`
  (JDK built-in) that streams `event: case-event` frames, supports `includePreviousEvents`, can
  drop/close the connection mid-stream, replay history, and emit `:keep-alive`. Drive the
  `AgentOsSseClient` against `http://localhost:<ephemeral-port>`. Inject a fast clock / short
  backoff so tests run in milliseconds. Alternatively, unit-test `SseFrameParser` and
  `VerdictDeriver` purely, and integration-test the client against the `HttpServer` double.

Required cases (each asserting the exact `AgentOsExecutionVerdict` subtype):

1. **Initial connection before execution** — connect, only live stream, no durable history → no
   premature verdict; when nothing terminal arrives within budget → `Indeterminate` (timeout), never
   `Succeeded`.
2. **Connection after start** — replay/live delivers `RUNNING` then `IDLE` + agent message →
   `Succeeded(outputs.summary=…)`.
3. **Connection after completion** — a fresh connection replays the full history (snapshot) and the
   stream auto-closes; final verdict derived correctly (`Succeeded` / `WaitingHuman` / `Failed`
   depending on the replayed terminal state).
4. **Connection drop & reconnection** — stream closes mid-turn; client reconnects, runs REST
   reconcile, and derives the correct verdict with **no duplicate processing** (assert each `eventId`
   handled once via a spy/counter on the dedup set).
5. **Duplicate event received twice** (present in both replay and live, or across a reconnect) →
   processed exactly once.
6. **`IDLE` with an unanswered `QuestionEvent`** → `WaitingHuman(questionRef=<questionId>,
   questionText=…)`. And an answered question (matching `AnswerEvent`) → not `WaitingHuman`.
7. **`IDLE` without structured output** (no question, no agent message) →
   `Indeterminate("Turn reached IDLE without structured output")`.
8. **`ERROR`** → `Failed("AGENT_CASE_ERROR", …)`; **`KILLED`** → `Failed("AGENT_CASE_KILLED", …)`;
   caller-initiated `interrupt` then `KILLED` → `Interrupted`. **Assert none is `Succeeded`.**
9. **Timeout / reconnection budget exhausted** → `Indeterminate("SSE reconnection budget exhausted")`
   (and the observation-timeout variant). Never a verdict by silence.
10. **Strict caseId filtering** — a frame carrying a foreign `caseId` is ignored and never affects the
    verdict or the checkpoint.
11. **Transient events ignored** — `ThinkingEvent`/`TextChunkEvent`/`CaseUpdatedEvent` in the live
    stream never advance the checkpoint nor produce a verdict.
12. **High-water mark** — after processing up to `(timestamp,id)`, a replay re-delivering earlier
    events is fully de-duplicated; a fresh start (empty mark) does a full replay + dedup.
13. **Idempotency** — `createOrRecoverExecution` called twice with the same `attemptId` creates the
    case once and recovers on the second call (assert only one `POST /api/cases`).
14. **`VerdictDeriver` pure unit tests** — table-driven over event lists + terminal status covering
    every rule branch.
15. **`SseFrameParser` pure unit tests** — multi-line `data:`, comment/heartbeat frames, `id:`
    extraction, blank-line dispatch, partial-buffer boundaries.

Also add a regression guard asserting the existing polling path is untouched: the current
`AgentOsAgentTurnTest` must still pass unmodified (do not edit it).

## Verification

The factory runs the affected test suite after the build. To validate locally / during debugging:

```bash
pnpm nx test factory-service
```

(or the Gradle-native `./gradlew :factory-service:test` from the module, matching the project's Nx→Gradle wiring).

Quality gates the factory can run:

- `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
- `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`

Acceptance:
- New package `io.whozoss.factory.adapter.agentos` compiles and all its tests pass.
- **All factory-service tests pass**, including the untouched `AgentOsAgentTurnTest`.
- No change to `HttpAgentOsProxyClient` behaviour (polling still active); no change under `agentos/`.
- No new **runtime** dependency added (JDK `HttpClient` + `com.sun.net.httpserver.HttpServer` only).
  If a dependency turns out to be unavoidable, add it test-scoped and note why.

## Out of scope / follow-ups (do not implement)

- Durable, restart-safe Factory-side persistence of the high-water mark and the attempt→case
  idempotency record (phase-1 is in-memory; the AgentOS-side durable store already exists).
- Wiring the adapter as the live turn driver in `SessionRunService` / replacing the polling capability.
- Any AgentOS core change (`Last-Event-ID`, sequence numbers) — explicitly deferred by the SSE contract doc.

## Risks & notes for the builder

- **Servlet vs streaming:** `factory-service` uses `spring-boot-starter-web` (servlet, blocking
  `RestClient`) — there is **no WebFlux/Reactor**. Do not pull in WebClient for SSE. Use the JDK
  `java.net.http.HttpClient` line-streaming body handler; it is already on the JDK 25 classpath.
- **Verdict-logic single source of truth:** the `IDLE`/question/agent-message parsing already exists in
  `HttpAgentOsProxyClient`. Extract the pure helpers into `CaseEventReasoning`/`VerdictDeriver` and,
  optionally, have the proxy delegate — but only if that refactor keeps every existing proxy test green.
  Safer: copy the logic into the adapter and leave the proxy byte-for-byte unchanged.
- **"Success by silence" divergence:** the adapter is deliberately stricter than the polling client —
  an `IDLE` with no question and no agent output is `Indeterminate`, not `Succeeded`. Keep this and
  document it; it is the core purpose of the ticket.
- Keep everything HTTP-boundary: no `import io.whozoss.agentos.*` anywhere in the new code.
