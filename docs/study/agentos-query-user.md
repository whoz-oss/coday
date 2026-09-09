# AgentOS — generic `queryUser`: analysis and implementation

> Code analysis carried out July 2026, **both** back end and front end verified.
> **Implemented August 2026** — see "What was built" near the end.
> The body of the document keeps the original analysis: it records the *why* behind the choices.

## The problem

An AgentOS agent could not ask the user a free-form question mid-run and resume its work with the
answer. There was no generic equivalent of Coday's `queryUser`.

## One-sentence summary

The model, the persistence, the REST transport and **the entire front end** were already shipped and
working. The only missing pieces were on the back end: a non-OAuth producer of `QuestionEvent`, and
waking the runtime up when the `AnswerEvent` arrives.

---

## What already existed

### 1. The confirmation gate (WZ-31596) — the right pattern

A tool opts into the gate by overriding `StandardTool.getConfirmationMode()`
(`NONE` / `INFER` / `EVERY_TIME`). The orchestrator then emits a `PendingConfirmationEvent`,
**ends the run**, and the state lives in the persisted events. Resumption happens through a
pre-flight check at the top of the run (`findUnresolvedPendingConfirmation()`), and the cycle closes
with a `ConfirmationResolvedEvent` paired via `pendingEventId`. It survives a server restart.

**Commonly misunderstood point**: the gate does NOT ask its question through a `QuestionEvent`. It
emits an in-channel `MessageEvent` — an ordinary agent message. The user's reply therefore arrives as
an ordinary USER `MessageEvent`, goes through `selectAgent`, produces an `AgentSelectedEvent`, and
restarts the runtime along the nominal path.

> The gate is robust **because it grafts onto the nominal path**, not because it has a resumption
> mechanism of its own.

Limitation: it is built on `AgentAdvanced`'s intention loop. `AgentSimple` does not benefit from it.

### 2. Interactive OAuth — blocking, to be reworked (#1198)

`OAuthFlowService.runInteractiveFlow()` emits a `QuestionEvent` of type `OAUTH_AUTHORIZE` then
**blocks** on `future.get(timeout)` inside a `withContext(Dispatchers.IO)`.

The price (accepted and documented in `OAuthPendingRegistry`'s KDoc): single-instance, does not
survive a restart, capped at roughly 64 concurrent flows by the IO pool, in-memory state.

This path cannot be made non-blocking cheaply: turning the chain into `suspend` would touch three
elements of the SDK's public contract.

### 3. `QuestionEvent` / `AnswerEvent` — complete chain except the wake-up

**What existed and worked:**

- SDK types, Neo4j persistence (`QuestionEventNode`, `AnswerEventNode`, tested bidirectional mapping)
- `questionType`, `options`, targeting `userId`, `createAnswer()`
- REST transport: `AddMessageRequest.answerToEventId`
- push notifications: `PushNotificationDispatcher`
- transcript rendering: `CaseTranscriptFormatter`
- **the front end, in full** (see below)

**What was missing — the wake-up.** In `CaseRuntime.addUserMessage`:

```kotlin
if (answerToEventId != null) { ...
    storeAndEmitEvent(questionEvent.createAnswer(actor, answerText))
    return // answer is passive — waits for agent to process it
}
```

No `selectAgent`, therefore no `AgentSelectedEvent`. `CaseServiceImpl.addMessage` does launch
`runtime.run()` behind it, but `processNextStep()` walks the history backwards, hits the previous
turn's `AgentFinishedEvent`, and returns `AGENT_FINISHED` → IDLE.

**Observable symptom** if an agent emitted a `SINGLE_CHOICE` today: the panel appears, the user
clicks, the panel switches to "✅ Question answered", the status flickers RUNNING then falls back to
IDLE, and **nothing happens**. No error, no hang — just silence.

**Only producer of `QuestionEvent` in production code**: `OAuthFlowService`, and only for
`OAUTH_AUTHORIZE`.

---

## The front end is complete (verified)

`QuestionPanelComponent` (`libs/agentos-ui/src/lib/components/question-panel/`) renders all four types:

| Type | Rendering |
|---|---|
| `FREE_TEXT` | text input + Submit, Enter to confirm |
| `SINGLE_CHOICE` | one button per option, click answers immediately |
| `OPEN_CHOICE` | option buttons **plus** a free-text field "Or type a custom answer…" |
| `OAUTH_AUTHORIZE` | Authorize / Cancel, delegates the popup to `OAuthAgentosService` |

`CaseChatComponent` has a `TimelineItem` of kind `'question'`, computes `answered` by looking for the
`AnswerEvent` paired on `questionId`, and shows "✅ Question answered" once replied to. Crucially,
`onQuestionAnswered()` does:

```typescript
this.isRunning.set(true)   // ← the front end explicitly expects the agent to resume
this.http.post(.../messages, { content: answer, answerToEventId: questionEvent.id })
```

`CompanionStateService` (PiP window) treats `QuestionEvent` as a **blocking** notification, plays a
chime, and clears it on `AnswerEvent`.

`OAuthAgentosService.answerQuestion()` is a public method whose KDoc explicitly says
"for FREE_TEXT, SINGLE_CHOICE, OPEN_CHOICE".

> **No front-end work was needed.** It was finished work waiting on the back end.

---

## Decision: `AgentInterrupt.AwaitAnswer` + pre-flight wake-up (A2)

### The vehicle: `AgentInterrupt.AwaitAnswer`

`AgentInterrupt` is a `sealed class` of exceptions used as a control signal from inside a tool.
**Its KDoc already documented `AwaitAnswer` as a planned member**: "suspend and wait for a human
answer to a QuestionEvent".

Why it is the right vehicle:

- `sealed class` → every `when` is checked at compile time. Adding a member without handling it is a
  **compilation error**, not a silent bug.
- It travels through `ToolCallback.call` (whose contract returns a bare `String`) without polluting
  the result. That is the explicit justification the KDoc gives for using exceptions here.
- It is handled by `emitInterruptAndFinishEvents`, **shared by `AgentSimple` AND `AgentAdvanced`** →
  both agents are covered for free.
- It touches **no public SDK type** (the class is internal to the service).

Shape: `AgentInterrupt.AwaitAnswer(question, options?, questionType, userId)`. The tool throws it →
`emitInterruptAndFinishEvents` emits `AgentFinishedEvent` + `QuestionEvent` → the run ends. Stateless,
persisted, survives a restart.

### Options rejected

| Option | Why not |
|---|---|
| `ToolExecutionResult.AwaitingUserInput` | In `AgentSimple` the tool result is consumed by Spring AI's internal loop, which feeds it back to the LLM as a `String` — there is no way to interrupt that loop from a return value. It would only work in `AgentAdvanced`. It also touches a public SDK type. |
| An event emitter on `ToolContext` | Breaks the "only the orchestrator emits" invariant, and reopens the door to OAuth-style blocking. |

### The wake-up: A2 (pre-flight), not A1 (active AnswerEvent)

Two routes were possible:

- **A1** — drop the `return` in `addUserMessage` and call `selectAgent`. Three lines.
- **A2** — a `findUnresolvedQuestion()` at the top of `run()`, symmetric with the confirmation gate's
  pre-flight.

**A2 was chosen.** The decisive reason is not architectural taste but a concrete risk:

During an OAuth flow the agent is **blocked inside its own run** (`runInFlight == true`, coroutine
suspended in `future.get()`). Yet the front end still posts an `AnswerEvent` when OAuth succeeds
(`OAuthAgentosService.postAnswer`), purely to close the question visually. That therefore happens
**while** the agent is still running.

With A1, that POST would trigger `selectAgent` → a spurious `AgentSelectedEvent` would be planted in
the history in the middle of an ongoing turn. The subsequent `run()` is correctly ignored
(`runInFlight` guards it), but `processNextStep()` works by **walking the history backwards** to the
first orchestration event it finds. Depending on the exact arrival order — which depends on the OAuth
popup's network latency — that stray event can trigger a phantom agent turn.

An intermittent, timing-dependent bug that cannot be reproduced locally. Not worth it to save three
lines.

**A2 is purely additive**: it changes no existing behaviour. Every current path, OAuth included,
bypasses it entirely.

### Pre-flight principle

> At the start of a turn, before anything else: is there a question that has received its answer and
> for which the agent never resumed? If so, emit an `AgentSelectedEvent` and let the normal loop do
> the rest.

Two conveniences offered by the existing model:

- **`QuestionEvent` already carries `agentId` and `agentName`.** We know exactly which agent to wake —
  no need to go back through `selectAgent` and its resolution cascade (`@` mention, last agent,
  default agent).
- **The guard protecting OAuth is a single condition**: only wake up if there is **no**
  `AgentFinishedEvent` after the `AnswerEvent`.
  - OAuth: the unblocked agent finishes its turn normally → `AgentFinishedEvent` arrives afterwards →
    no wake-up.
  - `AwaitAnswer`: the run ended *before* the answer → no `AgentFinishedEvent` after → wake-up.

---

## Implementation points

The initial plan had four. A re-read of the code before implementation revealed a decisive fifth
(point 5), implementation produced a sixth (refactor), and real-world testing exposed a seventh
(tool availability).

1. `AwaitAnswer` member in the `AgentInterrupt` sealed class
2. Its handling in `emitInterruptAndFinishEvents` (`QuestionEvent` after the `AgentFinishedEvent`)
3. The `ToolResponseEvent` message branches in `AgentSimple` and `AgentAdvanced`
4. The `queryUser` tool and its plugin (`QueryUserTool`, `QueryUserToolPlugin`)
5. The `findUnresolvedQuestion()` pre-flight at the top of `run()`
6. **Translating `QuestionEvent`/`AnswerEvent` into LLM messages** — see below
7. **Tool availability**: a default grant, not a declared integration — see below

### The fifth point, absent from the initial analysis

Neither `AgentSimple.convertEventsToMessages` nor `AgentAdvancedContext.convertEventsToMessages`
handled `QuestionEvent` and `AnswerEvent`: both fell into `else -> ignore`. Yet
`CaseEventType.isFirstLevel()` explicitly classes them as conversational alongside `MESSAGE`. The
intent was there, the translation was not.

Without this point, the first four produce an agent that resumes **blind**: neither its own question
nor the user's answer is in its context. It asks again, or hallucinates. A symptom as silent as the
missing wake-up, and harder to diagnose.

Why nobody had spotted it: the only producer of `QuestionEvent` was OAuth, where the agent stays
blocked *inside* its run and therefore never has to re-read the exchange from persisted history.
Exactly the same producer/consumer asymmetry noted under "Method note".

Mapping chosen: `QuestionEvent` → `AssistantMessage` (the agent's own voice, options listed when
present), `AnswerEvent` → `UserMessage` (consistent with how USER `MessageEvent`s are rendered).

### The seventh point, exposed by real-world testing

First live test: the agent did **not** call the tool. It wrote its question out in markdown, complete
with a bulleted list of the options. No `QuestionEvent`, therefore no panel — the front end was
behaving correctly.

Two chained causes:

- `ToolResolverService.resolveToolsForRun` only serves tools for integrations declared in
  `AgentConfig.integrations` (`agentIntegrations?.keys?.toList() ?: emptyList()`). With no
  `QUERY_USER` `IntegrationConfig` declared, the tool never reaches the agent.
- Worse, such an integration **cannot be created through the UI**:
  `CompositeIntegrationTypeRegistry.registerFromPlugin` does `plugin.configSchema ?: return`. Since
  `QueryUserToolPlugin.configSchema` is `null`, the type is never catalogued and never appears in the
  "Type" dropdown of the integration form.

**Resolution**: `QueryUserToolGrantService`, modelled on `ExchangeToolGrantService` — a parallel grant
path alongside the resolver, explicitly legitimised by `resolveToolsForRun`'s own KDoc.

Decision table (identical to the exchange pattern; both `containsKey` and `get` are required, since a
key that is absent and a key mapped to `null` are indistinguishable through `get` alone yet mean
opposite things):

| Condition | Result |
|---|---|
| key absent + `enabledByDefault = true` | granted |
| key absent + `enabledByDefault = false` | not granted |
| key present, value `null` | granted |
| key present, empty list `[]` | **opt-out** — not granted |
| key present, non-empty list | granted |

`QueryUserConfigProperties.enabledByDefault = true` (prefix `agentos.query-user`, registered in
`AgentOSApplication`). Asking a question is a primitive of conversation, not an integration like Jira:
agents should not have to declare it. This also matches the legacy Express backend, which users
expect.

**The empty-list opt-out is the escape hatch** for a fully autonomous agent triggered by a webhook:
nobody is listening, so an unanswered question would block the case indefinitely.

Wired into `AgentServiceImpl` through `dedupToolsByName`, so an agent that also has a declared
`QUERY_USER` `IntegrationConfig` does not end up with the tool twice. `configName = null` gives the
bare name `queryUser`.

---

## Recipient control — the permanent-deadlock trap

`QuestionEvent.userId` identifies the user on whose behalf the agent is running — the one whose answer
is awaited. `null` means "any user of the case" (webhook, system call); no artificial fallback is
applied. The value travels `ToolContext.userId` → `AwaitAnswer.userId` → `QuestionEvent.userId`, which
avoids touching `Agent`, a public SDK type.

The pre-flight verifies that the respondent is the intended recipient. **The check lives INSIDE the
`indexOfFirst` predicate, never after it.**

If the check were applied after the search:

1. Bob answers a question addressed to Alice.
2. `indexOfFirst` finds Bob's answer (first one paired by `questionId`).
3. The recipient check fails → return `null`.
4. Alice answers later.
5. `indexOfFirst` **still** finds Bob's answer first → stuck forever.

The question would be dead and the agent blocked for good, with no error anywhere. The KDoc on
`findUnresolvedQuestion` records this reasoning explicitly, because it is precisely the kind of
subtlety a future reader would "simplify" away.

Defensive case: an actor whose `actor.id` does not parse as a UUID (system actor, external identifier)
does not qualify as a legitimate answer; a debug log is emitted for diagnosability.

---

## What was built

Persistence, transport, rendering, notification, transcript: already there, untouched. No front-end
file modified. No public SDK type modified. `CaseRuntime.addUserMessage` unchanged — the
`return // answer is passive` is intact, the pre-flight is purely additive.

Files created:

- `agentos-service/.../queryUser/QueryUserTool.kt` — derives `QuestionType` from
  `options`/`allowCustomAnswer`, throws `AwaitAnswer` on the happy path, returns a readable error on
  invalid input (same contract as `RedirectTool`)
- `agentos-service/.../queryUser/QueryUserToolPlugin.kt` — plain `@Component`, `configSchema = null`
  (config-less plugin, conforming to the `ToolPlugin` contract). No `@Configuration`: unlike
  `RedirectToolPlugin` it has no Spring dependency to inject, hence no cycle risk.
- `agentos-service/.../queryUser/QueryUserToolGrantService.kt` — the default grant and its decision
  table
- `agentos-service/.../queryUser/QueryUserConfigProperties.kt` — platform-level policy

Files modified: `AgentInterrupt.kt`, `AgentInterruptHandler.kt`, `AgentSimple.kt`, `AgentAdvanced.kt`,
`AgentAdvancedContext.kt`, `CaseRuntime.kt`, `AgentServiceImpl.kt`, `AgentOSApplication.kt`,
`application.yml`.

Refactor along the way: the tool-call flush in `AgentSimple.convertEventsToMessages` had become
duplicated four times once the `QuestionEvent`/`AnswerEvent` branches were added. Extracted into
`flushPendingToolCalls()`, with a KDoc explaining the OpenAI invariant (any `AssistantMessage`
carrying `tool_calls` must be followed by a `ToolResponseMessage` covering every `tool_call_id`, on
pain of HTTP 400). It is the most fragile invariant in the file; leaving it copied guaranteed that a
future change would miss one occurrence.

Build: `./gradlew :agentos-service:build` green.

Confirmed working in production use: three consecutive question/answer cycles in a single case, the
tool appearing under its bare name `queryUser` (proving the default grant path), rendering as
`SINGLE_CHOICE` with option buttons and no free-text field.

---

## Trap encountered: `InMemoryCaseEventList` re-sorts by timestamp

Worth remembering for any future `CaseRuntime` test that manipulates event ordering.

`InMemoryCaseEventList.add()` inserts **chronologically**, and the constructor does
`inputEvents.sortedBy { it.timestamp }`. The order in which events are passed to `pushEvents` is
therefore **ignored**: only the timestamp counts. Since every `CaseEvent` defaults to
`timestamp = Instant.now()`, the order in which fixtures are *declared in the source* determines the
effective order — counter-intuitively.

The first three pre-flight tests built `questionEvent` and `answerEvent` before the `existingEvents`
list, whose other events are created inline and therefore later. After sorting, the
`AgentFinishedEvent` ended up *after* the `AnswerEvent`, the anti-OAuth guard fired, and the
pre-flight never ran.

The happy-path test failed outright (`runCalls` empty). More troubling: the two negative tests
**passed for the wrong reason** — they were not testing the ordering they claimed to test. A green
test that verifies nothing is worse than a red one, and here it covered the mechanism's most critical
guard.

Fix: explicit, strictly increasing timestamps (`Instant.EPOCH.plusSeconds(n)`) in all such tests, with
`.copy(timestamp = ...)` on the `AnswerEvent` produced by `createAnswer()`, which otherwise forces
`Instant.now()`.

---

## Known noise in this module's builds

`GlobalCaseEventSseController` repeatedly emits `MockKException: no answer found for
CaseService.findConcerningUser`, with a full stack trace, into the `system-out` of many specs.

It **predates this work and fails no test** (it is swallowed by a `catch` in the reconciliation loop),
but it saturates agent stdout buffers and derailed several diagnostics during implementation —
including one report that claimed tests were failing when they were in fact passing. Fix: stub
`findConcerningUser` in the affected spec. Still outstanding.

---

## Open question (non-blocking)

Was `return // answer is passive — waits for agent to process it` a deliberate choice or an unfinished
step? The comment describes an agent that *blocks* while waiting — that is, precisely the OAuth model.

**This does not block the implementation**: A2 being additive, it respects that choice either way. The
passive path stays passive; a door was added beside it.

---

## Key files

### Back end

- `agentos-service/.../agent/AgentInterrupt.kt` + `AgentInterruptHandler.kt`
- `agentos-service/.../agent/AgentServiceImpl.kt` — tool assembly, grant wiring
- `agentos-service/.../queryUser/` — tool, plugin, grant service, config properties
- `agentos-service/.../caseFlow/CaseRuntime.kt` — `addUserMessage`, `processNextStep`, `runTurns`,
  `run`, `findUnresolvedQuestion`
- `agentos-service/.../caseFlow/CaseServiceImpl.kt` — `addMessage`, `runAgent`
- `agentos-service/.../tool/ToolResolverService.kt` — declared-integration resolution
- `agentos-service/.../exchange/ExchangeToolGrantService.kt` — the pattern the grant service follows
- `agentos-sdk/.../tool/StandardTool.kt` — confirmation opt-in
- `agentos-sdk/.../caseEvent/CaseEvent.kt` — `QuestionEvent`, `AnswerEvent`, `PendingConfirmationEvent`
- `agentos-sdk/.../caseEvent/QuestionType.kt`
- `agentos-service/.../auth/OAuthFlowService.kt` — the only other producer of `QuestionEvent`

### Front end (already complete, no changes expected)

- `libs/agentos-ui/src/lib/components/question-panel/` — renders all 4 types
- `libs/agentos-ui/src/lib/components/case-chat/case-chat.component.ts` — `onQuestionAnswered`,
  timeline kind `'question'`
- `libs/agentos-ui/src/lib/services/companion-state.service.ts` — PiP notification
- `libs/agentos-ui/src/lib/services/oauth-agentos.service.ts` — `answerQuestion`, `postAnswer`

---

## Method note

A first pass of this analysis dismissed `QuestionType` as a "design fossil", based solely on back-end
producers — without looking at the consumers. That was wrong: the front end was complete. The lesson
is worth recording for future analyses of this repository: **a type with no producer may well have a
finished consumer**, and the asymmetry tells you about the order in which the work was done, not about
dead code.

The same asymmetry bit twice: point 5 (LLM message translation) was missed for exactly the same
reason — the only existing producer was OAuth, a path that never exercises the consumer.
