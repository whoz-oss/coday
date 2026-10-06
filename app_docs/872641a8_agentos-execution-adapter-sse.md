# AgentOS Execution Adapter boundary & robust SSE client (factory-service)

A new, **opt-in and additive** package `io.whozoss.factory.adapter.agentos` in `factory-service`
introduces an explicit Factory → AgentOS execution boundary: a typed verdict vocabulary, a robust
SSE observer, and a Spring adapter implementing the six boundary operations. The existing polling
turn driver (`HttpAgentOsProxyClient` via `AgentOsAgentTurnCapability`) is **untouched and remains
the active path**; the AgentOS core and the bridge plugin are not modified. The plan that drove the
work is recorded in `specs/872641a8_agentos_execution_adapter_sse.md`.

## Verdict vocabulary

`AgentOsExecutionVerdict.kt` — a standalone sealed interface (no dependency on the polling
client's `AgentTurnExecutionResult` nor on any run/attempt model):

- `Succeeded(outputs, evidence)` — the turn produced structured output.
- `WaitingHuman(questionRef, questionText?, evidence)` — IDLE with an unanswered question.
- `Failed(code, message, evidence)` — terminal `ERROR` / `KILLED`.
- `Interrupted(reason, evidence)` — terminal `KILLED` after a caller-initiated `interrupt(...)`.
- `Indeterminate(reason, evidence)` — the "never a verdict by silence" bucket: observation
  timeout, exhausted reconnection budget, IDLE without structured output, or a case that has not
  reached a quiescent/terminal status.

`VerdictDeriver.kt` is a pure function over the durable, ordered events of a case. Its strict
rules: `RUNNING` proves only that execution started (returns *null* — keep observing); `IDLE` is
evaluated explicitly (unanswered `QuestionEvent` with no matching `AnswerEvent.questionId` →
`WaitingHuman`; a non-blank agent `MessageEvent` → `Succeeded` with `outputs={"summary": …}`;
otherwise `Indeterminate(IDLE_WITHOUT_OUTPUT)` — deliberately stricter than the polling client,
which treats question-less IDLE as success even with an empty summary); `ERROR` →
`Failed(AGENT_CASE_ERROR)`; `KILLED` → `Failed(AGENT_CASE_KILLED)` unless an interrupt intent was
recorded, in which case `Interrupted`. Terminal `KILLED`/timeout can never become `Succeeded`.
Evidence (`turnFacts`) mirrors the polling client's shape: `caseId`, `caseStatus`, `agentTurns`,
`toolCalls`, `modifiedFiles`.

## SSE client and dedup/checkpointing

`AgentOsSseClient.kt` — observes one case over
`GET /api/cases/{caseId}/events?includePreviousEvents=true` using the JDK `HttpClient` (servlet
stack, no WebFlux), sending the trusted `X-External-User-Id` and `X-Factory-Attempt-Id` /
`X-Factory-Capability-Token` headers. Protocol enforced per `app_docs/agentos-sse-contract.md`:

- The server honours no cursor, so every (re)connection replays the full durable history; dedup
  is client-side by `eventId` through an `EventCheckpoint`.
- **Every reconnection runs a REST catch-up first** (`reconcile` callback against
  `GET /api/case-events/by-parentId/{caseId}`), so no durable event is missed while the stream
  was down.
- Strict caseId filtering: frames for another case are dropped before any logic.
- Transient events (`ThinkingEvent`, `TextChunkEvent`, `CaseUpdatedEvent`) are display-only —
  dropped before verdict and checkpoint logic.
- Reconnection uses bounded exponential backoff (defaults: 500 ms base, 30 s cap, budget of 5);
  exhaust → `Indeterminate("SSE reconnection budget exhausted")`.
- Stall detection: no frame and no `:keep-alive` within `stallTimeoutMs` (default 60 s) ⇒ the
  stream is treated as dropped.
- Wall-clock budget elapsed → `Indeterminate("SSE observation timeout")`. Silence never yields a
  verdict.
- A reader daemon thread feeds an `ArrayBlockingQueue` of lines so the stall window can interrupt
  a blocked read.

`HighWaterMark.kt` — `EventCheckpoint` per `(caseId, attemptId)`: a monotone
`(timestamp, lastEventId)` high-water mark plus a bounded (10 000) rolling window of seen ids; an
id evicted from the window stays covered by the mark (valid because AgentOS durable ordering is
`timestamp ASC, id ASC`). A fresh checkpoint means "full replay + eventId dedup".
`HighWaterMarkStore` is a process-local `ConcurrentHashMap` — losing it on restart fails safe
(fresh start), never a verdict by silence; durable persistence is an explicit follow-up.

`SseFrameParser.kt` — pure, line-fed SSE wire parser: blank line dispatches accumulated `data:`
lines (joined with `\n`) with the last `id:`/`event:`; `:…` comment lines are heartbeat frames;
unknown fields ignored; `finish()` flushes an unterminated trailing frame.

`CaseEventView.kt` — the HTTP-boundary event model. The adapter never imports an AgentOS type;
events are decoded from JSON `Map<String, Any?>` (discriminant `type`, e.g. `CaseStatusEvent`),
with accessors for `status`, question text, answered-question correlation
(`AnswerEvent.questionId` → `QuestionEvent.id`), agent-message detection (`actor.role == "AGENT"`)
and content flattening, plus `QUIESCENT_STATUSES = {IDLE, KILLED, ERROR}`.

## The adapter

`AgentOsExecutionAdapter.kt` defines the boundary (idempotency keyed by `attemptId`):
`createOrRecoverExecution(...)` (returns `CaseHandle(caseId, namespaceId, recovered)`),
`startTurn(caseId, persona, brief, ...)`, `observeTurn(caseId, attemptId, timeoutMs)`,
`reconcile(caseId)`, `interrupt(caseId, reason)`, `kill(caseId)`.

`DefaultAgentOsExecutionAdapter.kt` implements it with Spring `RestClient` for REST and the SSE
client factory for observation:

- `createOrRecoverExecution` posts `/api/cases` (body carries `id`, `attemptId`,
  `capabilityToken`) once per `attemptId`; a repeat call (or a concurrent race via
  `putIfAbsent`) recovers the existing `CaseHandle`. The idempotency record is process-local —
  losing it creates a new case (fail-safe).
- `startTurn` first reads the last `CaseStatusEvent` over REST and throws
  `AgentOsCaseBusyException` when the case is not quiescent (mirroring the `AGENT_CASE_BUSY`
  rule: posting to a busy case would be silently abandoned), then posts
  `{"content": "@persona brief"}` to `/api/cases/{caseId}/messages`.
- `observeTurn` resolves the execution record (headers, credentials) and the per-`(caseId,
  attemptId)` checkpoint, then observes over SSE with the REST reconcile callback wired in.
- `reconcile` is REST-only catch-up; a non-quiescent case yields
  `Indeterminate(NOT_QUIESCENT)` with the current status in evidence.
- `interrupt` records the interruption reason (so a later terminal `KILLED` derives to
  `Interrupted`, never `Succeeded`) and posts the kill; `kill` is best-effort
  `POST /api/cases/{caseId}/kill` and never throws (mirrors `killQuietly`).

## Wiring — disabled by default

`AgentOsAdapterConfiguration.kt` registers the bean **only** when
`factory.adapter.agentos.enabled=true`; the AgentOS base URL is reused from
`factory.proxy.agentosUrl` (`ProxyProperties`). `AgentOsAdapterProperties.kt` binds
`factory.adapter.agentos.*` (`enabled=false` default, backoff base/max, `maxReconnects`,
`stallTimeoutMs`, `observationTimeoutMs=600 000`). Nothing in `SessionRunService` references the
adapter.

## Tests

All under `factory-service/src/test/kotlin/io/whozoss/factory/adapter/agentos/`:

- `FakeAgentOsSseServer` — in-process SSE double on the JDK `HttpServer`; each connection
  dequeues a script of raw SSE lines with optional delays/hold, so drop/stall/replay scenarios
  are scriptable. Tracks `connectionCount`.
- `AgentOsSseClientTest` — initial connection before execution (timeout → `Indeterminate`),
  connect-after-start and connect-after-completion (replay derives the final verdict), drop +
  reconnect + REST catch-up with exactly-once processing across replay/live/reconcile, duplicate
  frame processed once, `IDLE` + unanswered question → `WaitingHuman`, `IDLE` without output →
  `Indeterminate`, `ERROR`/`KILLED` → `Failed` (never `Succeeded`), exhausted reconnect budget →
  `Indeterminate` (3 connections for budget 2), stalled stream treated as dropped, foreign-case
  frames ignored, transient events never reach verdict/checkpoint, warm high-water mark dedups
  the replayed prefix.
- `DefaultAgentOsExecutionAdapterTest` — `MockRestServiceServer` for REST + the fake SSE server:
  attemptId idempotency (single `POST /api/cases`), factory headers, busy-case refusal,
  reconcile → `WaitingHuman` / `Indeterminate(NOT_QUIESCENT)`, interrupt → `Interrupted` on
  terminal `KILLED`, best-effort kill never throws, `observeTurn` success from stream alone and
  via REST catch-up after a drop.
- `HighWaterMarkTest`, `SseFrameParserTest`, `VerdictDeriverTest` — unit coverage of dedup/window
  eviction/monotone mark, the wire parser (multi-line data, heartbeats, unknown fields, flush),
  and every verdict rule.

Verify with:

```
pnpm nx test factory-service
```

## Known limitations (by design, documented in code)

- The `attemptId` idempotency record and the `HighWaterMarkStore` are process-local; a restart
  falls back to full replay + dedup (safe) but durable Factory-side persistence is a follow-up.
- The adapter is not yet consumed by any capability or service — enabling the flag only creates
  the bean.
