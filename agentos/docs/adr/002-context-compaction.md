# ADR 002 — Context compaction for long-running agent cases

## Status

Proposed — answers the design questions of issue #1285. Implementation is split into the
phases listed under [Decision](#decision); each phase is a separate PR.

## Context

Long-running cases, especially Software Factory runs that call several agents one after the
other, can outgrow the provider's context window. Issue #1285 first said these terminations
were caused by context overflow. Its 2026-09-07 update says the cause is **not established**:
it could be the context limit, an iteration limit, or another error. This ADR first describes
what the code does today. Several assumptions in the issue no longer match it.

### Inventory (as of this ADR)

**Model limit registry: the field exists but nothing reads it.**
`AiModel.contextWindow: Long?` (SDK, `aiProvider/AiModel.kt`) is persisted (`AiModelNode`),
exposed through the REST API (`AiModelDto`), and read from filesystem YAML
(`FilesystemAiModelRepository`). No runtime code reads it. None of the bundled
`agentos/aimodel/*.yaml` files sets it. So item 4 of the issue ("introduce a registry") already
exists as a data model. What is missing is (a) values and (b) a consumer.

**Token usage per call: measured, but cumulative, and only when usage tracking is on.**
`UsageTrackingChatModel.trackedCall`/`trackedStream` take the provider's native usage from
every request (`CostCalculator.extract`). They record it in a per-invocation
`UsageAccumulator`, which only keeps **cumulative** totals. For a single call,
`inputTokens + cacheReadTokens + cacheWriteTokens` is exactly the prompt size, which is the
signal compaction needs. But:
- it is summed and then thrown away; the size of the last prompt is never kept;
- the wrapper only exists when `agentos.usage.enabled=true`, which is **off by default**;
- `CostCalculator` gives no input count for Gemini or other non-Anthropic/OpenAI types.

**Existing compaction: only in ADVANCED mode, and character-based.**
- `AgentAdvancedContext.convertEventsToMessages` uses `ToolReplayPlanner` with
  `maxDetailedChars = 300_000`. Tool exchanges are replayed in full from newest to oldest until
  that budget is used up. Older ones become one-line `[Step summary]` messages. This is a
  sliding window over tool payloads only; user and agent messages are always replayed.
- `AgentSimple` calls the same planner with `maxDetailedChars = Int.MAX_VALUE`: **no
  compaction at all**.
- In SIMPLE mode, most of the growth happens **inside one run**: the tool loop in
  `UsageTrackingChatModel.callRound`/`streamRound` (or Spring AI's own loop when usage is
  disabled) appends each tool result to `conversationHistory` without any limit. Compacting
  only when events are turned into messages would never reach this growth.
- `CompressingChatClient` shortens UUIDs. That is useful, but it is not context management.

**Error handling: overflow is already shown as an error, but without saying what it is.**
A provider 4xx (Anthropic `prompt is too long`, OpenAI `context_length_exceeded`) becomes a
`NonTransientAiException`, either directly or through `unwrapToProviderAiException` on the
streaming path. Both agents catch it and call `emitProviderErrorAndFinishEvents`, which emits
an `ErrorEvent` plus an `AgentFinishedEvent`. The case goes back to **IDLE**, not KILLED.
What is lost is the *classification*: the provider body is deliberately left out of the
message (it can hold sensitive data). The user sees `400 BAD_REQUEST from POST …` and cannot
tell an overflow from a schema error.

**What actually moves a case to a terminal status:**
- `KILLED` only comes from an explicit `requestKill()` (user or API).
- `ERROR` comes from `CaseRuntime.runTurns` hitting `caseMaxIterations` (100 steps per user
  message, counting every agent run and redirect in the turn), or from an unexpected exception
  escaping the runtime.
- A delegating parent sees a sub-case's `ERROR`/`KILLED` as `TERMINAL_STATUS`, and a silent
  sub-case as `TIMEOUT`.

A context overflow on its own therefore does **not** kill a case today. For the Factory
terminations, `caseMaxIterations` and delegation timeouts are at least as plausible. This
supports the issue's update: we must be able to *tell these apart* before building
compaction.

## Options considered

1. **Sliding window (truncation).** Cheap, and already used for tool payloads in ADVANCED
   mode. Its main problem is a cost, not a correctness issue: **the start of the prompt changes
   on every turn**, so prompt caching (#1274 / #1538) never hits once the window starts moving.
   It also loses the initial task and plan.
2. **Summarisation.** One utility LLM call condenses an old part of the history. It keeps
   meaning and adds one call's latency and cost *per compaction*, not per turn. Between two
   compactions the start of the prompt is stable, so caching keeps working.
3. **Hierarchical memory.** Long-term direction. It needs a memory store and retrieval;
   out of scope (issue).
4. **Model-aware budgeting.** Not a strategy on its own, but the **trigger** that every
   strategy needs.
5. **Provider switch on overflow.** Needs routing aware of context size; out of scope
   (issue).

## Decision

**Summarisation (2), triggered by model-aware budgeting (4), stored as a durable case event.**
It ships in three phases. Each phase is useful on its own, and phase 0 answers the open
question of the issue.

### Phase 0 — Diagnose: name the overflow

- Add a small classifier to `util/WebClientExceptionUtils.kt` that recognises
  context-overflow responses from their status and body *pattern* (Anthropic
  `prompt is too long`, OpenAI/vLLM `context_length_exceeded`, Gemini
  `exceeds the maximum number of tokens`). It returns a `ContextWindowExceededException`
  that extends `NonTransientAiException`, so all existing `catch` blocks still apply. The body
  stays out of the message: only the classification is propagated.
- `emitProviderErrorAndFinishEvents` emits an explicit `ErrorEvent` for it, for example: "The
  conversation exceeds the context window of model X; …". The `AgentFinishedEvent` gets a
  `contextWindowExceeded` marker, like `maxIterationsReached`, so a delegating parent receives
  it in its `DelegationResult`.
- Make the other terminal causes just as visible. `CaseRuntime` stores a `WarnEvent` naming
  `caseMaxIterations` before moving to `ERROR`; today this only goes to the log.

This needs no new configuration. After it ships, one Factory run is enough to answer the
issue's open question.

### Phase 1 — Measure: a context budget per agent run

- **Fill the registry:** set `contextWindow` in the bundled `aimodel/*.yaml` files and
  document it as recommended. Unknown (`null`) means "no proactive compaction", never a
  default guess.
- **Separate measurement from cost tracking:** a lightweight `ContextBudget` per invocation
  keeps the *last observed prompt size*. It is fed by the provider usage of every request
  (the same seam as `trackedCall`/`trackedStream`). When native usage is missing, it falls back
  to a character estimate (≈ chars / 4). This observation must run even when
  `agentos.usage.enabled=false`. Only cost estimation and the cost gate stay behind that switch.
- **Threshold:** `agentos.limits.context-compaction-threshold` (a fraction, default `0.8`). The
  budget is reached when `lastPromptTokens ≥ threshold × contextWindow − maxCompletionTokens`.

### Phase 2 — Compact: summarisation as a durable event

- **New durable event** `ContextCompactedEvent(summary, coversUpToEventId, model, tokensBefore)`.
  It is event-sourced and **non-destructive**: the original events stay in the store, the UI
  and the audit trail. Only the *prompt view* changes. Replay and rehydration rebuild the same
  prompt, because the boundary is persisted rather than recomputed.
- **Single prompt-view rule** shared by `AgentSimple.convertEventsToMessages` and
  `AgentAdvancedContext.convertEventsToMessages`. Events up to the latest
  `ContextCompactedEvent.coversUpToEventId` are replaced by one summary message. The first user
  message (the initial task) and all current-turn events are always kept in full. Tool
  request/response pairs are never split.
- **`ContextCompactor` service** (a new `compaction/` package in `agentos-service`). It
  produces the summary with the agent's own model capped by `forUtilityCall()` (the existing
  pattern for utility calls). It goes through the run's `UsageAccumulator`, so the cost is
  counted and gated like any other request.
- **Where it triggers:**
  - *Between runs* (the common case): before an agent run builds its prompt, if the last
    observed budget was reached, compact first.
  - *Within a SIMPLE run*: when the budget is reached between two tool rounds, end the round
    loop **through the existing max-tool-rounds path** in `UsageTrackingChatModel`. That path
    already asks the model for a progress summary without tools and finishes the run with a
    marker. The run finishes with `contextBudgetReached`, and the next run starts from a
    compacted history. This reuses a graceful-degradation path that already works instead of
    rewriting Spring AI's message list during the run.
  - ADVANCED mode keeps `ToolReplayPlanner` as its first line of defence. The character
    budget can later be derived from `contextWindow` instead of being fixed at 300 000.
- **Continuation:** a run that stopped on the budget behaves like one that stopped on
  `maxIterationsReached`. A user gets a Continue action. A delegating parent gets the flag and
  resumes the sub-case. Automatic continuation is left to a later iteration.

## Consequences

- Prompt caching stays effective: the start of the prompt changes once per compaction instead
  of on every turn. This is the main reason to prefer summarisation over a wider sliding
  window.
- One new durable event type (SDK + OpenAPI + client rendering). It only *adds* a summary
  layer: older clients that ignore it still show the full history correctly.
- Measuring context no longer depends on the usage switch. The usage wrapper must be split
  into an always-on observation part and an optional cost part.
- Summaries lose detail. Keeping the original events means a later strategy (hierarchical
  memory, retrieval) can be built from the full record.
- Models without `contextWindow` keep today's behaviour, plus the explicit phase 0
  diagnosis.

## Out of scope

Hierarchical memory, routing by context size, and sharing context across cases (as stated
in #1285).
