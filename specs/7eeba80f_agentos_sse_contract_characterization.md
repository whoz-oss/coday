# Plan Task: AgentOS SSE Contract Characterization & Factory Bridge Analysis

## Context
Goal: Freeze through automated characterization tests and thorough codebase analysis the exact contract of AgentOS SSE before implementing any Factory -> AgentOS bridge integration. Document real guarantees, gaps, and provide explicit recommendations in `app_docs/agentos-sse-contract.md`.

## Deliverables & Acceptance Criteria
1. **Documentation (`app_docs/agentos-sse-contract.md`)**:
   - Comprehensive analysis answering point-by-point all questions (URL & auth, format/discriminant, durability/transience, ordering, replay via `includePreviousEvents`, `eventId`, `Last-Event-ID`/cursor, post-completion connection, saturation/heartbeat, handling multi-turn/attempts/turnId/runId/commandId, and factory-bridge-plugin status/gaps).
   - Explicit Java/Spring/Kotlin class and method proofs for all facts.
   - Explicit, reasoned RECOMMENDATIONS for:
     a) Observation protocol (replay + deduplication vs cursor).
     b) Core modification vs non-invasive fallback for idempotency/turnId.
2. **Characterization Tests**:
   - Automated tests added to `agentos/agentos-service` (or corresponding test module) covering key SSE behaviors: connection before execution, connection mid-run, connection post-completion (replay/snapshot), disconnection/reconnection & `eventId` deduplication, heartbeat & buffer saturation error behavior.
   - NO runtime production code behavior modifications in AgentOS.
3. **Quality Gates**:
   - All characterization tests pass green.
   - Existing test suite (`pnpm nx affected -t test`) and builds pass without regressions.

---

## Phase 1: Exhaustive Analysis & Draft Document `app_docs/agentos-sse-contract.md`

### 1.1 Detailed SSE & Bridge Findings (Proven by Source Code)

#### Point 1: URL, Endpoint, Auth & OpenAPI
- **URL**: `GET /api/cases/{caseId}/events`
- **Controller & Method**: `CaseEventSseController.streamEvents` (`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseController.kt`)
- **Query Params**: `includePreviousEvents: Boolean` (defaults to `true`).
- **Auth**: Spring Security `@PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")` + `@HideOnAccessDenied`. Token passed via standard Spring Security mechanism (Bearer HTTP Authorization header or cookie depending on security filter chain). Query param auth is NOT built-in.
- **OpenAPI Tag**: `@Tag(name = "sse")` with `@Operation(tags = ["sse"])`. Explicitly excluded from Angular/TypeScript client SDK generation via `ng-openapi-gen.json` (`tag: "sse"` filter). Clients must use browser `EventSource` / reactive SSE client.

#### Point 2: Format & Discriminant
- **SSE Channel Name**: Always `event: case-event` (constant `CaseEventSseController.CASE_EVENT_CHANNEL = "case-event"`).
- **Discriminant**: JSON payload contains `"type": "<SubtypeName>"` (Jackson Polymorphic Deserialization on `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")` on `CaseEvent` seal/interface in `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/caseEvent/CaseEvent.kt`).
- **Data Payload**: Full JSON serialization of the `CaseEvent` domain object (`event.id`, `event.parent`, `event.createdAt`, `event.type`, plus subtype specific fields like `content`, `step`, `error`, etc.).

#### Point 3: Durability vs Transience
- **Persistence**: Persisted synchronously to database via `CaseEventService.appendEvent` / `CaseEventRepository` (`R2DBC` / PostgreSQL table `case_event`).
- **Live Stream**: Broadcast in-memory via `CaseRuntime.events` (`SharedFlow<CaseEvent>` / Kotlin Coroutines).
- **Life Cycle / Retention**: Case events in PostgreSQL are durable forever (no TTL or automatic purge in `CaseEventService`).

#### Point 4: Ordering & Sequence
- **Ordering in DB**: `CaseEventService.findByParent(caseId)` sorts events by `createdAt ASC, id ASC` (or DB sequence).
- **Ordering in Stream**: Live events emitted in FIFO order via Kotlin `Channel<CaseEvent>(LIVE_BUFFER_CAPACITY)` with UNDISPATCHED subscription before DB read.
- **Sequence Field**: `CaseEvent` does NOT have an explicit `sequenceNumber` integer column; ordering relies on `createdAt` timestamp + `UUID` tie-breaker.

#### Point 5 & 6: Replay, `includePreviousEvents`, `Last-Event-ID` & `eventId`
- **Replay**: Supported via `includePreviousEvents=true` (default). When `true`, fetches all historical events from `CaseEventService.findByParent(caseId)` and emits them before draining the live channel.
- **`Last-Event-ID` Support**: `CaseEventSseController` does **NOT** read or parse the standard `Last-Event-ID` HTTP header or a `lastEventId` query parameter. It always replays from the very beginning of the case if `includePreviousEvents=true`.
- **`eventId`**: Format is UUID (`event.id: UUID`), formatted as standard 36-char string (`emitter.send(SseEmitter.event().id(event.id.toString())...)`. Unique per event globally.
- **Deduplication in Controller**: Uses an in-memory `emittedEventIds = mutableSetOf<UUID>()` during the lifetime of a single SSE connection session to deduplicate between DB replay and live channel buffer.

#### Point 7: Post-Completion & Stream Lifecycle
- **Active Case vs Finished Case**:
  - If `caseService.findActiveRuntime(caseId)` returns `null` (case finished / inactive), `CaseEventSseController` replays historical events from DB, then immediately calls `emitter.complete()` and closes the stream.
  - If case is ACTIVE (`findActiveRuntime` returns non-null), stream stays open indefinitely (`SseEmitter(0L)` infinite timeout) waiting for live events or heartbeat.

#### Point 8: Saturation, Backpressure & Heartbeat
- **Heartbeat**: Periodic SSE comment frame `event().comment("keep-alive")` sent every `caseConfig.sseHeartbeatIntervalMs` (default 15,000ms / 15s). Failing write cancels coroutine scope.
- **Backpressure / Saturation**: `LIVE_BUFFER_CAPACITY = 100`. If live buffer overflows (`trySend` fails) or `deliveryFailureCount > 0`, connection is forcibly closed with `SseConnectionSaturatedException` and `emitter.completeWithError(cause)`. This forces the SSE client to reconnect and perform a clean durable DB replay without data loss or gaps.

#### Point 9: Multi-turn / Attempts / Turn Mapping in Core
- **`CaseEvent` Schema**: Contains `id`, `parent` (caseId), `createdAt`, `type`. It does **NOT** have native fields for `turnId`, `runId`, `attemptId`, or `commandId`.
- **Execution Flow**: Turns in AgentOS are implicit within `CaseRuntime`. Commands/Inputs are pushed into `CaseRuntime.submitUserMessage(...)` or step execution.

#### Point 10: Factory Bridge Plugin Status & Gaps
- **Plugin Components**: `agentos-factory-bridge-plugin` (`FactoryStepResultBindingController`, `FactoryStepResultBindingRegistry`, `FactoryCaseLifecycleObserver`, `FactoryExternalExecutionContextProvider`).
- **Binding Registry**: `FactoryStepResultBindingRegistry` stores `FactoryStepResultBinding` tokens (`caseId`, `namespaceId`, `workerId`, `attemptId`, `runtimeId`, `capabilityToken`, `expiresAt`) in a `ConcurrentHashMap<UUID, FactoryStepResultBinding>` in memory.
- **Gaps for Factory Bridge**:
  1. **In-memory state**: `FactoryStepResultBindingRegistry` is completely in-memory. Server restart loses all active bindings and capability tokens.
  2. **No Turn / Attempt Idempotency in AgentOS Core**: AgentOS does not record Factory `turnId` or `commandId` in `CaseEvent` or `CaseState`. A retry from Factory re-triggers step/case execution without built-in core deduplication.
  3. **No Cursor/Offset Replay in SSE**: Standard `Last-Event-ID` header is ignored by `CaseEventSseController`. Reconnecting requires re-reading all historical events for the case from event #1.

---

## Phase 2: Formulate Recommendations for `app_docs/agentos-sse-contract.md`

### Recommendation A: Observation Protocol (Factory -> AgentOS Bridge)
- **Protocol Decision**: The Factory Bridge client must connect with `includePreviousEvents=true` and perform client-side deduplication based on `event.id` (UUID) or offset tracking on `createdAt`.
- **Rationale**: AgentOS SSE does not currently parse `Last-Event-ID` to seek into DB events from a given index. However, because `CaseEventSseController` replays all events from DB in exact timestamp order (`findByParent`) and deduplicates in-connection events, the client can safely maintain a `seenEventIds` set or store `lastProcessedCreatedAt` and discard events prior to its last ack'd checkpoint.

### Recommendation B: Idempotency & Turn Mapping Decision (Core vs Fallback)
- **Decision**: **Fallback / Bridge-level mapping WITHOUT modifying AgentOS Core runtime in Phase 1.**
- **Rationale**:
  - Modifying AgentOS Core schema and `CaseRuntime` engine introduces widespread breaking changes across all AgentOS plugins, DB migrations, and reactive flows.
  - Factory bridge plugin can maintain the mapping of `(FactoryAttemptId, FactoryTurnId) -> AgentOS CaseId / Binding` in its state store (or external storage).
  - Characterization tests will freeze current AgentOS core behavior as-is, ensuring zero runtime risk. Core enhancements for durable `turnId` / `Last-Event-ID` can be scheduled in a dedicated core evolution wave.

---

## Phase 3: Characterization Test Suite Implementation Plan

### 3.1 Test Location & Architecture
- **Location**: `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseCharacterizationSpec.kt`
- **Framework**: Kotest (`StringSpec`), Spring Boot Test / Mockito / MockK or unit setup using real/mocked `CaseService`, `CaseEventService`, and `CaseConfigProperties`.
- **Reference Example**: Existing `CaseEventSseControllerUnitSpec.kt` in `agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/`.

### 3.2 Key Behaviors to Characterize via Tests
1. **Test 1: Connection Before Execution (Live Stream)**
   - Connect SSE to an active case with no historical events.
   - Emit live events through `CaseRuntime.events`.
   - Verify `SseEmitter` receives events with channel `case-event`, ID matching `event.id`, and correct JSON body.

2. **Test 2: Connection After Execution Start (Replay + Live Stream)**
   - Seed historical `CaseEvent`s in `CaseEventService`.
   - Connect SSE with `includePreviousEvents = true`.
   - Verify historical events are delivered first in order.
   - Emit a new live event and verify it is delivered afterwards without duplication.

3. **Test 3: Connection Post-Termination (Snapshot Replay & Auto-Close)**
   - Setup inactive/completed case (`caseService.findActiveRuntime` returns `null`).
   - Connect SSE with `includePreviousEvents = true`.
   - Verify all historical events are delivered, followed by immediate stream completion (`onCompletion` / `complete()`).

4. **Test 4: Disconnection, Reconnection & Deduplication**
   - Connect, receive events `E1` and `E2`.
   - Simulate disconnect and reconnect with `includePreviousEvents = true` while `E2` and `E3` are present.
   - Verify client receives `E1, E2, E3` in order and `CaseEventSseController` internal set deduplicates appropriately during a single stream run.

5. **Test 5: Saturation & Buffer Overflow Behavior**
   - Fill live channel buffer beyond `LIVE_BUFFER_CAPACITY` (100).
   - Verify controller triggers `completeWithError` with `SseConnectionSaturatedException`.

6. **Test 6: Heartbeat Generation**
   - Configure low `sseHeartbeatIntervalMs` (e.g. 10ms).
   - Verify comment frame `:keep-alive` is sent periodically.

---

## Phase 4: Verification & Execution Steps

### Step 1: Draft Documentation
- Create/update `app_docs/agentos-sse-contract.md` with complete findings, code references, and recommendations.

### Step 2: Implement Characterization Tests
- Create `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseCharacterizationSpec.kt`.
- Write unit/integration test cases using Kotest `StringSpec`.

### Step 3: Run Tests & Quality Checks
- Run Gradle / Nx test target for `agentos-service`:
  `./agentos/gradlew -p agentos :agentos-service:test --tests "io.whozoss.agentos.caseEvent.CaseEventSseCharacterizationSpec"`
- Run overall affected tests baseline check:
  `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`

---

## File Modifications Summary

1. `app_docs/agentos-sse-contract.md` (New/Updated documentation file)
2. `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseCharacterizationSpec.kt` (New test file)
