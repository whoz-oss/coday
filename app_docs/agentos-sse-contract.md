# AgentOS SSE contract — characterization & Factory → AgentOS bridge recommendations

> Status: investigation / characterization. This document freezes the **real** SSE contract of
> AgentOS as implemented today, proves every statement against source code, and gives explicit
> recommendations before any Factory → AgentOS bridge is built.
>
> Runtime code was **not** modified by this investigation. The only additions are this document
> and the characterization suite `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseCharacterizationSpec.kt`.

## 0. TL;DR — the guarantees a bridge may rely on

| Question | Answer (today) |
|---|---|
| Endpoint | `GET /api/cases/{caseId}/events` (`CaseEventSseController.streamEvents`) |
| Auth | Spring Security `@PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")`; identity resolved by `AgentOsAuthenticationFilter` (X-External-User-Id / Cloudflare JWT / Bearer JWT / x-forwarded-email in `auth` mode; OS user in `local` mode). No SSE-specific auth. |
| Wire format | Single SSE channel `event: case-event`; `id:` = `CaseEvent.id` (UUID); `data:` = the JSON-serialized `CaseEvent`, discriminated by `type` (class simple name). |
| Durability | Durable events are persisted (Neo4j). `TransientCaseEvent` subtypes (`ThinkingEvent`, `TextChunkEvent`, `CaseUpdatedEvent`) are **never** persisted and **not** replayed. |
| Ordering | Durable: `timestamp ASC, id ASC` (Neo4j query). Live: per-runtime FIFO. Replay is emitted before the live drain. |
| Replay | `includePreviousEvents=true` (default) replays the **full** history from event #1 on every connection. `includePreviousEvents=false` replays nothing. |
| Cursor | **No** `Last-Event-ID` support, **no** `lastEventId` query param, **no** sequence number. Every reconnect re-reads the whole history. |
| `eventId` | `CaseEvent.id` (UUID). Stable across replay and live; the controller dedups by it **within a single connection**. |
| Post-termination | If `findActiveRuntime(caseId) == null`, the controller replays the persisted history and completes the stream. |
| Saturation | If the runtime’s live buffer rejects an emission, or the per-connection buffer (capacity 100) overflows, the stream is closed with an error so the client reconnects and replays durably. |
| Heartbeat | SSE comment frame `:keep-alive` every `agentos.case.sse-heartbeat-interval-ms` (default 30 s). |
| Multi-turn identity | `CaseEvent` carries **no** `turnId`, `runId`, `attemptId` or `commandId`. Attribution lives only in `sessionContext`/Factory-side state. |
| Factory bridge | Plugin components exist but are **volatile/in-memory**; the host does **not** wire the binding transport. No idempotency key in the core. |

The two headline recommendations:

- **Observation protocol (A):** connect with `includePreviousEvents=true` and deduplicate client-side by
  `eventId` (replay-complete + dedup), because AgentOS does not implement a durable cursor. Persist the
  last acknowledged `(timestamp, id)` and reconcile via the REST listing endpoint.
- **Core change (B):** **do not** modify the AgentOS core for turn/attempt idempotency in phase 1.
  Enforce idempotency at the Factory boundary (attempt-scoped outbox + `attemptId` on the result channel).
  A minimal core evolution (honor `Last-Event-ID`) is scoped as an opt-in follow-up, not a prerequisite.

---

## 1. URL, endpoint, auth & OpenAPI surface

**Proof — `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseController.kt`**

- `:44` `class CaseEventSseController(caseService, caseEventService, caseConfig)`
- `:41` `@Tag(name = "sse", …)`, `:42` `@RestController`, `:43` `@RequestMapping("/api/cases")`
- `:80` `@GetMapping("/{caseId}/events", produces = ["text/event-stream"])`
- `:81` `@PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")`, `:82` `@HideOnAccessDenied`
- `:83-85` `fun streamEvents(@PathVariable caseId: UUID, @RequestParam(defaultValue = "true") includePreviousEvents: Boolean = true): SseEmitter`

So the URL is `GET /api/cases/{caseId}/events?includePreviousEvents=true|false`.

**Auth.** There is no SSE-specific authentication. Identity is resolved per request by
`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/security/declarative/AgentOsAuthenticationFilter.kt`
(`doFilterInternal` → `userService.getCurrentUser()` → `SecurityContextHolder`), then `@PreAuthorize`
enforces `READ` on the `Case`. `AuthSecurityService` (`security/AuthSecurityService.kt`) accepts, in order:
`X-External-User-Id` header, Cloudflare JWT, `Authorization: Bearer` JWT, `x-forwarded-email`. In `local`
mode the OS user is used (`SecurityConfiguration.kt`). A **server-to-server bridge can set headers**;
a browser `EventSource` cannot, so the Angular client relies on same-origin/cookie-session and only the
`local`/cookie-based deployments are directly usable from the browser.

**OpenAPI.** The endpoint is tagged `sse` and **is** emitted into `agentos/openapi/agentos-openapi.yaml`
(see the `/api/cases/{caseId}/events` entry, tag `sse`), and is generated as `SseService`
(`libs/agentos-api-client/src/lib/api/sse.service.ts`). That generated stub is unusable for real streaming
(the Angular `HttpClient` is not an `EventSource`). The actual consumer is the hand-written
`libs/agentos-api-client/src/custom/case-event-sse.service.ts`, which opens a native `EventSource` and
listens on `case-event`. Conclusion: **the SSE endpoint is not “excluded” from codegen, it is
codegen-representable-but-hand-consumed**; the bridge must not use the generated client.

---

## 2. Wire format & discriminant

**Proof — `CaseEventSseController.kt`**

- `:235` `const val CASE_EVENT_CHANNEL = "case-event"`
- `:209-212` `sendEvent` → `SseEmitter.event().id(event.id.toString()).name(CASE_EVENT_CHANNEL).data(event)`
- Doc block `:51-62`: every domain event is sent on the single stable `event: case-event` channel;
  consumers must discriminate on `data.type`. This replaced per-type SSE event names (breaking change).

**Proof — `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/caseEvent/CaseEvent.kt`**

- `:68` `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")`
- `:91` `sealed interface CaseEvent : Entity` with `namespaceId`, `caseId`, `timestamp`, `type`
- `:22-65` `enum class CaseEventType` maps each subtype to its **class simple name**
  (`MessageEvent`, `CaseStatusEvent`, `ToolResponseEvent`, `QuestionEvent`, …).

Therefore each frame is:

```
event: case-event
id: 2b0a…-…            ← CaseEvent.id (UUID)
data: {"type":"MessageEvent","id":"2b0a…","caseId":"…","timestamp":"…","actor":{…},"content":[…]}

```

The SSE `id` equals `entity.id`, i.e. `metadata.id` (`sdk/entity/Entity.kt` → `val id get() = metadata.id`).

---

## 3. Durability vs transience

**Proof — `CaseEvent.kt`**

- `:105-113` `interface TransientCaseEvent` (“never written to the event store; never pushed into the runtime’s
  in-memory event list”).
- Transient subtypes: `ThinkingEvent` (`:274`), `TextChunkEvent` (`:391`), `CaseUpdatedEvent` (`:517`).
- Durable subtypes: `MessageEvent`, `CaseStatusEvent`, `ToolRequestEvent`, `ToolResponseEvent`,
  `QuestionEvent`, `AnswerEvent`, `AgentSelectedEvent`, `AgentRunningEvent`, `AgentFinishedEvent`,
  `WarnEvent`, `ErrorEvent`, `IntentionGeneratedEvent`, `ToolSelectedEvent`, `PendingConfirmationEvent`,
  `ConfirmationResolvedEvent`, `SubCaseStartedEvent`, `SubCaseFinishedEvent`.

**Proof — `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImpl.kt`**

- `:748-751` `storeEvent(event)`: `is TransientCaseEvent -> event` (returned as-is), otherwise
  `caseEventService.create(event)` (persisted).
- `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseEvent/CaseEventServiceImpl.kt`
  `create/update → repository.save`; `agentos/.../caseEvent/Neo4jCaseEventRepository.kt` `save`
  writes the node and links `CaseEvent → Case`.

**Consequence for the bridge.** A reconnecting observer sees only durable events. Transient events
(live “typing”, streaming chunks, title refresh) are lost on disconnect **by design** and must never be
used to derive bridge state. Durable state is reconstructible from replay.

---

## 4. Ordering & sequence

**Durable order — `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseEvent/CaseEventNodeNeo4jRepository.kt:19-24`**

```
MATCH (e:CaseEvent) WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
… RETURN e, r, c ORDER BY e.timestamp ASC, e.id ASC
fun findActiveByCaseId(caseId: String): List<CaseEventNode>
```

`CaseEventRepository`/`CaseEventService.findByParent` contract is “ordered by timestamp (oldest first)”
(`caseEvent/CaseEventRepository.kt`, `caseEvent/CaseEventService.kt`). The id is the deterministic
tie-breaker; **there is no monotonic sequence number column**.

**Live order — `CaseEventSseController.kt:150`** the runtime flow is collected with
`CoroutineStart.UNDISPATCHED`, i.e. subscription happens synchronously *before* the persistence read,
and events queue in a per-connection `Channel<CaseEvent>(LIVE_BUFFER_CAPACITY=100)` (`:135`), drained FIFO
(`:186-190`). Replay (`:170-177`) is emitted before the live drain.

**Mixed order.** The stream is “all durable history (repo order) then queued live events (arrival order)”.
There is no global merge by timestamp; a live event that predates the last durable one cannot reorder it.
The controller dedups by id so the same event never appears twice (`:205-206`).

---

## 5. Replay, `includePreviousEvents`, `eventId`, `Last-Event-ID` / cursor

**Proof — `CaseEventSseController.kt`**

- `:85` `includePreviousEvents` defaults to `true`.
- `:169-177` when true: `caseEventService.findByParent(caseId).forEach { sendIfNew(...) }` — the **whole**
  history, from the first event.
- `:205-206` `sendIfNew`: `if (emittedEventIds.add(event.id)) sendEvent(...)`. `emittedEventIds` is a
  per-connection `mutableSetOf<UUID>()` (`:168`), so it dedups history vs live **within one connection**.
- The controller never reads `Last-Event-ID`, never takes a cursor/offset query param, and no `SseEmitter`
  `reconnectTime`/replay window is used. Grep confirms the string `Last-Event-ID` does not appear in
  `agentos-service`.

**`eventId` format.** `event.id.toString()` → canonical 36-char UUID string, stable across replay and live.

**Consequence.** Reconnection is **always a full replay**. A durable cursor does not exist. Deduplication
must be client-side keyed by `eventId`; the per-connection set is not shared between reconnections.

---

## 6. Post-termination connection (snapshot replay & auto-close)

**Proof — `CaseEventSseController.kt`**

- `:134` `val activeCase = caseService.findActiveRuntime(caseId)`.
- `:178-180` `if (activeCase == null) { emitter.complete(); return@launch }` — after the (optional)
  replay, the stream closes immediately.
- `:150-165` the live subscription and delivery-failure watcher are installed **only** when `activeCase != null`.

**`findActiveRuntime` semantics — `CaseServiceImpl.kt`**

- `:212` `override fun findActiveRuntime(caseId: UUID): CaseRuntime? = activeRuntimes[caseId]` — it never
  rehydrates; a case with no in-memory runtime returns `null`.
- `:253-280` `startEvictionWatcher`: an **IDLE** runtime is evicted after
  `agentos.case.idle-eviction-grace-ms` (default 300 000 ms, `CaseConfigProperties.kt`) once all SSE
  subscribers disconnect (`subscriptionCount == 0 && status == IDLE`).
- `:765-825` `handleStatusChange`: on a **terminal** status (`KILLED`/`ERROR`, `CaseStatus.isTerminal()`)
  the status event is emitted, then the runtime is removed (`:816-818`).

So:
- While a case is `PENDING`/`RUNNING`/`IDLE`, `findActiveRuntime != null` → the stream stays open
  (`SseEmitter(0L)`, infinite timeout, `:89`).
- After terminal status, or after the idle-eviction grace, `findActiveRuntime == null` → a new connection
  replays and closes. Existing connections to a killed case also get the final `CaseStatusEvent` before
  eviction (`CaseServiceImpl.kt:811-818`).

---

## 7. Saturation, backpressure & heartbeat

**Runtime side — `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseEvent/DefaultCaseEventEmitter.kt`**

- `:21-25` `MutableSharedFlow(replay = 0, extraBufferCapacity = 100)`.
- `:47-55` `emit` uses `tryEmit`; a rejected emission increments `_deliveryFailureCount`
  (a `MutableStateFlow`). Exposed as `DefaultCaseEventEmitter.deliveryFailureCount`, surfaced through
  `CaseRuntime.deliveryFailureCount` (`CaseRuntime.kt:182`).

**Connection side — `CaseEventSseController.kt`**

- `:138-147` `invalidateForSaturation`: logs, `emitter.completeWithError(cause)`, `scope.cancel()`. The
  exception is `SseConnectionSaturatedException` (“SSE live buffer saturated … reconnect to replay
  durable events”).
- `:150-156` live collector uses **non-blocking** `trySend`; a full 100-slot per-connection channel
  triggers the saturation path (never drops silently).
- `:160-163` a separate watcher awaits `deliveryFailureCount.drop(1).first()` and triggers the same path
  when the runtime itself rejected an event.
- `:214-227` heartbeat: every `caseConfig.sseHeartbeatIntervalMs` send `SseEmitter.event().comment("keep-alive")`
  (wire form `:keep-alive\n`). A failed write cancels the whole scope (reliable disconnect detection).

**Config — `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseConfigProperties.kt`**

- `sseHeartbeatIntervalMs` default **30 000 ms** (env `AGENTOS_CASE_SSE_HEARTBEAT_INTERVAL_MS`).
- `idleEvictionGraceMs` default **300 000 ms** (env `AGENTOS_CASE_IDLE_EVICTION_GRACE_MS`).

**Consequence.** Backpressure is turned into an explicit **error, then reconnect-and-replay**, never into
event loss. The bridge must treat a dropped connection as “replay from the start and dedup”, not as a gap.

---

## 8. Multi-turn / attempts / `turnId` / `runId` / `commandId`

**Proof — `CaseEvent.kt:91-95`.** The base interface carries only `namespaceId`, `caseId`, `timestamp`,
`type` (plus inherited `id`/`metadata`). There is **no** `turnId`, `runId`, `attemptId`, `commandId`, or
sequence field on any `CaseEvent` subtype.

**Turns in the core.** A “turn” is implicit in `CaseRuntime` (`caseFlow/CaseRuntime.kt`): user messages
(`addUserMessage`), commands (`enqueueCommand`), and status transitions `RUNNING → IDLE` are not correlated
by an id on the events. The only per-run attribution carried on events is tool-call scoping
(`ToolRequestEvent.toolRequestId`, `QuestionEvent.id` ↔ `AnswerEvent.questionId`,
`ConfirmationResolvedEvent.pendingEventId`).

**What is available to correlate a Factory attempt:** the Factory chooses the **caseId** at creation
(`CaseController.create` accepts `resource.id`, `caseFlow/CaseController.kt` → `EntityMetadata(id = resource.id ?: …)`),
and can thread an `attemptId` through `sessionContext` (persisted on the user `MessageEvent.sessionContext`)
or via the bridge plugin’s execution-context SPI. But the core does not use it to deduplicate execution.

---

## 9. Factory bridge plugin — status & gaps

All under `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/`.

**Components**

- `FactoryStepResultBindingRegistry.kt`
  - `:16-26` `data class FactoryStepResultBinding(caseId, namespaceId, agentName, attemptId, runtimeId, capabilityToken, expiresAt, leased: AtomicBoolean)`.
  - `:39` `ConcurrentHashMap<UUID, FactoryStepResultBinding>` keyed by **caseId** (one binding per case).
  - `:41-45` `bind`: requires future `expiresAt`, token length 32..256, `putIfAbsent` (conflict if present).
  - `:47-60` `context(caseId, namespaceId, agentName)` / `:69-84` `contextForCase(caseId, namespaceId)`:
    return the capability only when live, unexpired, namespace-matching and **not leased**.
  - `:86-93` `acquire`: `leased.compareAndSet(false, true)` (CAS) — single-flight lease.
  - `:95-100` `acknowledge`: `remove` + `leased=false`.
  - `:102-107` `release`: `leased=false` (keeps the binding, retryable).
  - `:109-114` `invalidate`: `remove` + `leased=false` (fail-closed).
  - `:116-118` `remove(caseId)`, `:120` `contains`.
  - `:122-152` `validated`: expiry / namespace / agent checks; expired entries are self-evicted.
  - Class KDoc: “volatile, in-memory registry … a restart loses every capability and therefore fails closed”.
- `FactoryStepResultBindingController.kt`
  - `:49-78` `bind(caseId, suppliedSecret, request)`: constant-time shared-secret check (`Unauthorized`,
    endpoint disabled on blank secret), case→namespace resolution (`NotFound`), namespace/identity
    non-blank check (`Conflict`), then `registry.bind` (`Conflict` on duplicate/invalid). Outcome enum
    `FactoryBindingOutcome { Bound, Unauthorized, NotFound, Conflict }`. **No HTTP transport of its own** —
    “the AgentOS host exposes the transport”.
- `FactoryCaseLifecycleObserver.kt` (`@Extension : CaseLifecycleObserver`)
  - `onStatusChanged`: on terminal status removes the binding (`stepResultBindings.remove(caseId)`) and the
    pending checkpoint; `onEventStored`: debug log only.
- `FactoryExternalExecutionContextProvider.kt` (`@Extension : ExternalExecutionContextProvider`)
  - `provideExecutionContext` → `registry.contextForCase(caseId, namespaceId)` (empty when no binding).
- `FactorySubmitStepResultTool.kt` (`FACTORY__submit_step_result`)
  - `:56-63` resolves case/agent, `bindings.acquire(...)`, POSTs `${baseUrl}/api/factory/agent-step-results`
    with `Authorization: Bearer <capabilityToken>`, `X-AgentOS-Case-Id`, `X-AgentOS-Agent-Name`.
  - `:70-80` HTTP outcome mapping: success → `acknowledge`; 5xx/`RESULT_SCHEMA_INVALID` → `release`
    (retryable); other 4xx → `invalidate`; transport exception → `release`.
- `FactoryBridgeServices.kt` / `FactoryBridgePluginHolder.kt`: plugin-scoped singletons (registry, HTTP
  client, pending checkpoints). Created on `FactoryBridgePlugin.start()`, cleared on `stop()`.
- `FactoryBridgeConfig.kt`: `agentos.factory.base-url` / `AGENTOS_FACTORY_BASE_URL` (default
  `http://localhost:8141`), `agentos.factory.runtime-id` / `AGENTOS_FACTORY_RUNTIME_ID`.

**Gaps that block a durable Factory → AgentOS bridge**

1. **Bindings are in-memory only.** `FactoryStepResultBindingRegistry` is a `ConcurrentHashMap` created per
   plugin start; a restart loses every capability and lease. By design it fails closed (no replay of an
   old capability), but that means **no resumable observation** across restart.
2. **The host does not wire the binding transport.** No class under `agentos/agentos-service/src/main/kotlin`
   references `FactoryStepResultBindingController`, `FactoryStepResultBindingRequest`, `FactoryBindingOutcome`
   or the `X-Factory-*` headers (the Factory-side `HttpAgentOsProxyClient` sends `X-Factory-Attempt-Id` /
   `X-Factory-Capability-Token`, but agentos-service ignores them; only the Factory’s own
   `factory-service/.../web/FactoryStepResultBindingController.kt` exists, and it is the *verify* endpoint).
   Consequently, in a running service the registry is never populated and
   `FactoryExternalExecutionContextProvider` always returns `{}`.
3. **The plugin is not active by default.** `agentos-service` loads plugins from `agentos.plugins.dir`
   (`application.yml:230-231`, `config/PluginConfigProperties.kt`, default `plugins/`, currently empty). The
   bridge plugin must be installed as a PF4J jar to be discovered.
4. **No turn/attempt idempotency in the core** (see §8). A retried Factory step re-drives the case; the
   core provides no dedup key.
5. **No durable cursor in the SSE contract** (see §5). Reconnect = full replay; the bridge must own the
   dedup/checkpoint.
6. **Pending checkpoints are in-memory too** (`FactoryBridgeServices.pendingCheckpoints`, a
   `ConcurrentHashMap`).

**Current Factory-side transport is polling, not SSE.** `factory-service/src/main/kotlin/io/whozoss/factory/proxy/HttpAgentOsProxyClient.kt`
`executeAgentTurn` creates the case, posts the brief, then `awaitQuiescence` **polls**
`GET /api/case-events/by-parentId/{caseId}` (`listEvents`, `findStatus`, `lastUnansweredQuestion`) until a
quiescent `CaseStatusEvent` (`IDLE`/`KILLED`/`ERROR`). The SSE endpoint is available but not yet consumed by
the Factory. This is the code the SSE bridge is meant to replace/supplement.

---

## 10. Recommendations

### A. Observation protocol (Factory → AgentOS bridge)

**Decision: replay-complete + client-side dedup on `eventId`; do not rely on a cursor.**

Rationale, grounded in §5:

1. AgentOS exposes no cursor (`Last-Event-ID` ignored, no `lastEventId`, no sequence). Every connection is a
   full replay from event #1 when `includePreviousEvents=true`.
2. `eventId` is a stable UUID present on every frame, identical between the replayed and live copies, and the
   controller already dedups within a connection.
3. On saturation/disconnect AgentOS deliberately **closes with an error and forces a reconnect** (§7); the
   reconnect replays durably. So “replay + dedup” is the only gap-free protocol the server actually supports.

Concrete protocol:

- Always connect with `includePreviousEvents=true` (never rely on the default implicitly; set it explicitly).
- Maintain a durable `Map<eventId, ack>` (or at least `lastAcknowledged={timestamp,id}`) on the bridge side.
  On every (re)connection, discard frames whose `eventId` is already acknowledged; process the rest in order.
- Because ordering is `timestamp ASC, id ASC`, a persisted high-water mark `(timestamp, id)` is a valid
  incremental checkpoint even without server support — but always fall back to “replay everything and dedup”
  after a restart, since the server cannot resume from it.
- Treat `TransientCaseEvent`s as display-only; never derive bridge decisions from them (§3).
- Use `GET /api/case-events/by-parentId/{caseId}` (`CaseEventRestController.listByCase`,
  `caseEventRest` tag) for periodic reconciliation / catch-up when SSE is unavailable; it returns the same
  durable, `timestamp ASC`-ordered events. `GET /api/cases/{caseId}` returns the persisted `status` for a
  cheap liveness check.
- Bound the `seen` set: keep a window of acknowledged ids (e.g. keyed by case + a rolling window on
  `(timestamp,id)`), since a case’s history is unbounded and durable forever.

### B. Idempotency / turn attribution — modify the core or fallback?

**Decision: fallback at the bridge boundary without modifying the AgentOS core runtime in phase 1.**

Rationale:

- Adding `turnId`/`attemptId`/`commandId` to `CaseEvent` would be a schema + persistence migration and an
  SDK change touching every plugin and all event (de)serialization — for a correlation need the Factory can
  satisfy on its own side.
- Adding `Last-Event-ID`/cursor support would similarly touch the core controller, the repo query, and the
  event model; it is a real improvement but not required to make the bridge correct (recommendation A is
  gap-free without it).
- The Factory already owns the authoritative attempt identity and a durable outbox
  (`AgentStepResultService`, `factory-service/.../agentattempt/web/AgentStepResultController.kt`, verified by
  `AgentStepResultControllerHttpTest`). Idempotency belongs there: key submissions by `attemptId`, make the
  result channel `attemptId`-deduplicated, and treat AgentOS cases as evidence producers rather than
  idempotency stores.
- Keeping the core untouched also keeps the characterization suite meaningful: it freezes today’s behaviour,
  so any future core change that would alter the contract is caught by `CaseEventSseCharacterizationSpec`.

**Scoped, optional core follow-ups (not prerequisites, to schedule deliberately):**
1. Honor `Last-Event-ID` (or a `?lastEventId=` param) in `CaseEventSseController`: when present, replay only
   events after the referenced id instead of the whole history. Small, additive, backward compatible (absent
   header → current behaviour).
2. A monotonic per-case sequence number on persisted events (authoritative ordering, enabling cheap
   `seq > last` catch-up) — larger change, only if replay cost becomes a problem.

**Missing bridge work that must precede any durable bridge (regardless of A/B):**
- Wire the host transport for `FactoryStepResultBindingController.bind` (an agentos-service controller that
  reads the shared secret and X-Factory headers from case creation) **or** move the binding to a durable
  store.
- Persist bindings/leases and pending checkpoints (the current `ConcurrentHashMap`s are restart-volatile).
- Provide a restart-safe bridge-side checkpoint for the SSE high-water mark.

---

## 11. Characterization test coverage map

`agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseCharacterizationSpec.kt`
(Kotest `StringSpec`, passed green — 8 tests, 0 failures). It uses the real `CaseEventSseController` and
`DefaultCaseEventEmitter`, mocking only `CaseService`/`CaseEventService`; it asserts on the frames buffered
by `SseEmitter` before servlet initialization (no Tomcat needed).

| Test | Behaviour frozen |
|---|---|
| connection before execution | live-only stream, `event: case-event`, `id:` = `CaseEvent.id` |
| connection after execution start | durable history replayed first, then live, in order, no duplication |
| `includePreviousEvents=false` | no durable read at all (`findByParent` never called); live only |
| connection after termination | history replayed then the stream completes (`complete=true`, no failure) |
| reconnection without a cursor | full replay from event #1 on a fresh connection (no `Last-Event-ID`) |
| dedup by `eventId` | an event present in both history and the live flow is emitted once |
| saturation | a rejected runtime emission → `completeWithError` carrying the “saturated” error |
| heartbeat | periodic `:keep-alive` comment frames |

No runtime code was modified. Existing suites and builds are unaffected.

---

## Appendix — code proof index

| Fact | File:line |
|---|---|
| Endpoint, `@PreAuthorize`, `includePreviousEvents` default | `agentos-service/.../caseEvent/CaseEventSseController.kt:80-85` |
| Infinite timeout `SseEmitter(0L)` | `…/CaseEventSseController.kt:89` |
| `findActiveRuntime` gate | `…/CaseEventSseController.kt:134,178-180` |
| Live channel capacity / UNDISPATCHED subscribe | `…/CaseEventSseController.kt:135,150` |
| Saturation → `completeWithError` | `…/CaseEventSseController.kt:138-147,160-163` |
| Replay then drain, per-connection dedup | `…/CaseEventSseController.kt:168-190,205-206` |
| Frame id/name/data | `…/CaseEventSseController.kt:209-212` |
| Heartbeat comment | `…/CaseEventSseController.kt:214-227` |
| `CASE_EVENT_CHANNEL` / `LIVE_BUFFER_CAPACITY` | `…/CaseEventSseController.kt:235-236` |
| Polymorphic `type` discriminant | `agentos-sdk/.../sdk/caseEvent/CaseEvent.kt:22-65,68,91` |
| `TransientCaseEvent` + subtypes | `agentos-sdk/.../sdk/caseEvent/CaseEvent.kt:105-113,274,391,517` |
| `CaseStatus` / `isTerminal` | `agentos-sdk/.../sdk/caseFlow/CaseStatus.kt:15-23` |
| Runtime emitter buffer + failure counter | `agentos-service/.../caseEvent/DefaultCaseEventEmitter.kt:18-33,41,47-55` |
| `CaseRuntime` exposure | `agentos-service/.../caseFlow/CaseRuntime.kt:179,182,196-201,223` |
| `findActiveRuntime`, eviction, terminal eviction | `agentos-service/.../caseFlow/CaseServiceImpl.kt:212,253-280,765-825` |
| Transient not persisted | `agentos-service/.../caseFlow/CaseServiceImpl.kt:748-751` |
| Durable ordering query | `agentos-service/.../caseEvent/CaseEventNodeNeo4jRepository.kt:19-24` |
| REST listing (polling alternative) | `agentos-service/.../caseEvent/CaseEventRestController.kt:108-113` |
| Heartbeat/eviction defaults | `agentos-service/.../caseFlow/CaseConfigProperties.kt` |
| Identity resolution | `agentos-service/.../security/declarative/AgentOsAuthenticationFilter.kt`, `security/AuthSecurityService.kt` |
| Hand-written SSE consumer | `libs/agentos-api-client/src/custom/case-event-sse.service.ts` |
| Factory-side capability verify | `factory-service/.../agentattempt/web/FactoryStepResultBindingController.kt` |
| Factory-side polling turn driver | `factory-service/.../proxy/HttpAgentOsProxyClient.kt` |
| Bridge registry semantics | `agentos-factory-bridge-plugin/.../factorybridge/FactoryStepResultBindingRegistry.kt` |
| Bridge lifecycle observer | `agentos-factory-bridge-plugin/.../factorybridge/FactoryCaseLifecycleObserver.kt` |
| Bridge execution-context provider | `agentos-factory-bridge-plugin/.../factorybridge/FactoryExternalExecutionContextProvider.kt` |
| Bridge result submission tool | `agentos-factory-bridge-plugin/.../factorybridge/tools/FactorySubmitStepResultTool.kt` |

Spec counterpart: `specs/7eeba80f_agentos_sse_contract_characterization.md`.
