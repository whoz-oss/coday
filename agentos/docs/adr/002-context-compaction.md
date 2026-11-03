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
tell an overflow from a schema error. Nothing retries.

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

### Prior art: the Coday TypeScript runtime

The legacy TypeScript runtime (removed in `92a3b8c68`; see `libs/model/src/lib/ai-thread.ts`,
`ai.client.ts`, `ai-thread.helpers.ts` and `libs/handler/src/lib/anthropic.client.ts` in that
commit's parent; introduced by "avoid contextWindow overflow by compaction" and
"#149 summary events") already compacted conversations in production. What it did:

- **Budget before every LLM call**, including each round of the tool loop it owned:
  `charBudget = contextWindow × 3 − systemInstructions − toolDefinitions` (3 chars per token,
  deliberately below the usual 4 for margin). Default models carried `contextWindow` in code.
- **Hysteresis**: walking newest-first, messages are kept up to **70 %** of the budget, but the
  split only happens once the total exceeds **100 %**. Between two compactions the prompt start
  is stable, so the Anthropic cache marker stays valid.
- **Oversized tool results capped first**: any tool response above 50 % of the budget is cut
  to its last 15 % with a `[... truncated N chars]` prefix, before partitioning.
- **Durable `SummaryEvent`**, inserted in the thread right after the last summarised message.
  The prompt is built backwards up to the latest summary; the UI and export still see every
  message (`getAllMessages`).
- **Summary by the agent's own model** through `complete()`, output capped at budget / 20.
- **Tool-pair repair** after the cut: orphaned tool requests or responses are dropped, so the
  provider never rejects the history.
- **Reactive recovery**: on Anthropic `prompt is too long`, the overshoot is parsed from
  `N tokens > M maximum`, the budget is reduced by overshoot + 20 % (or by 30 % when it cannot
  be parsed), and the call is retried once with forced compaction.
- **Degraded fallback**: if the summary call fails, a placeholder
  (`[Previous conversation truncated due to context window constraints]`) stands in for the
  summary and the run continues with the recent messages.

What it got wrong, and this design must not repeat:

- The transcript sent to the summariser kept only user and agent messages: **tool calls and the
  previous summary were dropped**. A second compaction lost the first summary, and the record
  of what the agent actually did (files read, commands run) disappeared.
- When the transcript was too long for the summary call, it was **cut from the start**,
  which could drop the user's original request.
- The summary prompt was a bare `It can be summarized as:` with no structure.

## Options considered

1. **Sliding window (truncation).** Cheap, and already used for tool payloads in ADVANCED
   mode. Its main problem is a cost, not a correctness issue: **the start of the prompt changes
   on every turn**, so prompt caching (#1274 / #1538) never hits once the window starts moving.
   It also loses the initial task and plan.
2. **Summarisation.** One utility LLM call condenses an old part of the history. It keeps
   meaning and adds one call's latency and cost *per compaction*, not per turn. Between two
   compactions the start of the prompt is stable, so caching keeps working. Proven in Coday.
3. **Hierarchical memory.** Long-term direction. It needs a memory store and retrieval;
   out of scope (issue).
4. **Model-aware budgeting.** Not a strategy on its own, but the **trigger** that every
   strategy needs.
5. **Provider switch on overflow.** Needs routing aware of context size; out of scope
   (issue).

## Decision

**Summarisation (2), triggered by model-aware budgeting (4) with hysteresis, stored as a
durable non-destructive case event.** It ships in three phases. Each phase is useful on its
own, and phase 0 answers the open question of the issue.

### Phase 0 — Diagnose and recover once

- **Classify** context-overflow responses in `util/WebClientExceptionUtils.kt` from their
  status and body *pattern* (Anthropic `prompt is too long`, OpenAI/vLLM
  `context_length_exceeded`, Gemini `exceeds the maximum number of tokens`). The result is a
  `ContextWindowExceededException` that extends `NonTransientAiException`, so every existing
  `catch` block still applies. It carries the parsed `promptTokens` / `maxTokens` when the body
  provides them (`N tokens > M maximum`). The body itself stays out of the message.
- **Surface** it: `emitProviderErrorAndFinishEvents` emits an explicit `ErrorEvent` ("The
  conversation exceeds the context window of model X …"), and the `AgentFinishedEvent` gets a
  `contextWindowExceeded` marker, like `maxIterationsReached`, so a delegating parent receives
  it in its `DelegationResult`.
- **Name the other terminal causes**: `CaseRuntime` stores a `WarnEvent` naming
  `caseMaxIterations` before moving to `ERROR`; today this only goes to the log.

No new configuration. After it ships, one Factory run answers the issue's open question. The
one-shot retry after an overflow is part of phase 2, since it needs a compactor to retry with.

### Phase 1 — Measure: a context budget per LLM call

- **Fill the registry**: set `contextWindow` in the bundled `aimodel/*.yaml` files and document
  it as recommended. Unknown (`null`) means "no proactive compaction", never a default guess.
- **`ContextBudget`** (new, `agentos-service`, package `compaction/`), one per agent invocation:
  - `limit = contextWindow − maxCompletionTokens − fixedOverhead`, where the fixed overhead is
    the system message plus tool definitions;
  - `trigger = 0.8 × limit` (`agentos.limits.context-compaction-threshold`, default `0.8`);
  - `target = 0.5 × limit` (`agentos.limits.context-compaction-target`, default `0.5`). The gap
    between trigger and target is the hysteresis that keeps the prompt start stable;
  - **estimate** a prompt as characters / 3, as in Coday, and **correct** it with the provider's
    native prompt size whenever a response reports it (`inputTokens + cacheReadTokens +
    cacheWriteTokens`). The correction is a running chars-per-token ratio, so later estimates
    in the same run use the measured value.
- **Separate observation from cost**: the request wrapper that owns SIMPLE's tool loop and sees
  every request must exist even when `agentos.usage.enabled=false`. `UsageTrackingChatModel` is
  split into an always-on part (tool loop, round limit, context observation and compaction hook)
  and the optional cost part (accumulator, cost gate). Cost estimation and the cost gate stay
  behind the switch.

### Phase 2 — Compact: summarisation as a durable event

- **New durable event** `ContextCompactedEvent(summary, coversUpToEventId, model,
  tokensBefore, tokensAfter)`. It is event-sourced and **non-destructive**: the original events
  stay in the store, the UI and the audit trail. Only the *prompt view* changes. Replay and
  rehydration rebuild the same prompt, because the boundary is persisted rather than recomputed.
- **Single prompt-view rule** shared by `AgentSimple.convertEventsToMessages` and
  `AgentAdvancedContext.convertEventsToMessages`:
  1. events up to the latest `ContextCompactedEvent.coversUpToEventId` are replaced by one
     summary message;
  2. the **first user message** (the initial task) is always kept in full, before the summary;
  3. the current turn's user message is always kept in full;
  4. tool request/response pairs are never split; orphans left by a boundary are repaired the
     same way Coday did (`cleanToolRequestResponseConsistency`);
  5. tool results larger than 50 % of the budget are capped before anything else (keep the last
     15 %, prefixed with `[... truncated N chars]`).
- **`ContextCompactor`** (`compaction/`):
  - chooses the boundary by walking newest-first and keeping events up to `target`;
  - builds the transcript from **everything** being folded: the previous summary, user and agent
    messages, and tool calls as one-line entries (tool, key arguments, success or failure, short
    result). When the transcript itself is too long for one call, it is reduced from the
    **middle** (oldest tool results first), never from the start;
  - uses a structured prompt asking for: the goal and constraints, decisions taken, work done
    (files, commands, results), current state, and open items;
  - calls the agent's own model capped by `forUtilityCall()` (the existing pattern for utility
    calls), through the run's `UsageAccumulator`, so the cost is counted and gated like any
    other request;
  - **falls back** to the Coday placeholder when the summary call fails, and emits a `WarnEvent`
    so the degradation is visible.
- **Where it triggers**:
  - **Before each LLM call**, in both modes. For SIMPLE this includes every round of the tool
    loop owned by the always-on wrapper (Phase 1), as Coday did: when the round's prompt
    reaches `trigger`, the compactor rewrites the round's message list (system, first user
    message, summary, recent exchanges) and the `ContextCompactedEvent` is emitted through the
    agent's event flow, its boundary pointing at the last `ToolResponseEvent` already emitted.
    If mapping the in-loop messages to event ids proves unreliable in implementation, the
    fallback is to end the round loop through the existing max-tool-rounds path and compact at
    the start of the next run.
  - ADVANCED mode keeps `ToolReplayPlanner` as its first line of defence; its character budget
    is derived from `contextWindow` instead of being fixed at 300 000, and summarisation applies
    on top when the budget is still exceeded.
- **Reactive recovery**: on a `ContextWindowExceededException` (Phase 0), reduce the budget by
  the parsed overshoot + 20 % (or by 30 % without one), force a compaction and retry the call
  **once**. A second overflow is surfaced as in Phase 0. This also covers models without
  `contextWindow`: they get no proactive compaction, but they recover from an overflow.

## Consequences

- Prompt caching stays effective: the start of the prompt changes once per compaction instead
  of on every turn. This is the main reason to prefer summarisation over a wider sliding
  window, and the reason for the trigger/target hysteresis.
- One new durable event type (SDK + OpenAPI + client rendering). It only *adds* a summary
  layer: older clients that ignore it still show the full history correctly.
- SIMPLE's tool loop is always owned by AgentOS, not by Spring AI, even with usage tracking
  off. This removes a behaviour difference between the two settings (the round limit already
  depends on it today).
- Summaries lose detail. Keeping the original events means a later strategy (hierarchical
  memory, retrieval) can be built from the full record, and repeated summaries no longer lose
  earlier ones because the previous summary is part of what gets folded.
- Models without `contextWindow` keep today's behaviour plus the explicit diagnosis and the
  one-shot recovery.

## Out of scope

Hierarchical memory, routing by context size, and sharing context across cases (as stated
in #1285).
