# Multi-User & Delegation

## Runtime Scope: CaseRuntime

Each active case owns a `CaseRuntime` instance held in memory by `CaseServiceImpl`. The runtime is the unit of execution: it owns the event list, the SSE flow, the kill flag, and the main coroutine loop. It is callback-driven — all persistence and agent execution are delegated back to `CaseServiceImpl`, keeping the runtime itself free of Spring dependencies.

A `CaseRuntime` lives from the first `addMessage()` call until the case reaches a terminal status (`KILLED` or `ERROR`), at which point it is evicted from the in-memory map. A case in `IDLE` status still has a live runtime and open SSE connections; it resumes on the next user message without rehydration.

### Status lifecycle

```
PENDING → RUNNING → IDLE → RUNNING → IDLE ...   (normal turns)
                  → KILLED                        (explicit kill)
                  → ERROR                         (max iterations or exception)
```

Terminal statuses are permanent. `isTerminal()` returns true for `KILLED` and `ERROR`. The runtime is evicted *after* the terminal `CaseStatusEvent` is emitted so SSE clients always receive the final status.

## Multi-User: Current State

The `Actor` model (`id`, `displayName`, `role: ActorRole`) is already embedded in the event hierarchy. Every `MessageEvent` and `AnswerEvent` carries the originating actor. `ActorRole` has two values: `USER` and `AGENT`.

`CaseRuntime.addUserMessage()` takes an explicit `Actor` parameter — the service layer is responsible for resolving who is sending a message. `processNextStep()` identifies the most recent user message by scanning backward for `actor.role == USER`, which means the runtime is already multi-actor-aware at the event level.

What is **not yet implemented**:
- Per-request user identity propagation through the HTTP layer into the runtime
- SSE streams scoped to a specific user (currently one SSE flow per case, not per user-session)
- User-to-user interaction routing (the `ActorSelectedEvent` concept described below)

## ActorSelectedEvent — Product Target

The sprint forge discussions identified a need to route a turn not just to an agent but to a specific actor — human or AI. The concept: an `ActorSelectedEvent` would carry a target actor that could be:

- A **user** — triggers a push notification on their preferred channel
- A **group** — fan-out notification
- An **agent** — triggers a new agent run (current behaviour of `AgentSelectedEvent`)

This generalises the current agent-selection pattern into a unified actor-selection pattern. `AgentSelectedEvent` becomes a specialisation for the `AGENT` role case.

The SSE implication is significant: today the SSE flow is case-scoped. To support per-user notification, each browser session needs its own SSE connection scoped to the user (not the case), with a subscription model that fans events from cases to interested user sessions. This is a planned architectural evolution, not yet implemented.

## Delegation Patterns

AgentOS does not yet have a native delegation mechanism equivalent to Coday's `DELEGATE__delegate` tool. The notes below describe the Coday patterns that AgentOS will eventually need to support or replicate.

### Sync vs async

- **Sync delegation**: the parent waits for the sub-agent to finish before continuing. Result is returned inline. Safe for short-lived tasks.
- **Async / fire-and-forget**: the parent receives a `threadId` immediately and continues. The sub-thread runs independently. Used for long-running tasks or when the result is not needed inline.

The choice between sync and async has deep architectural implications: async delegation means the parent's SSE flow and the sub-thread's SSE flow are independent. Reconnecting to a case does not automatically surface sub-thread events — this is an open UX problem.

### ThreadId routing

A sub-thread is identified by its `threadId`. To resume a delegation (add new work to an existing sub-thread), pass the `threadId` to the delegate tool instead of creating a new thread. The agent in the resumed thread has full access to its prior history.

### Known limitations (Coday, as of 2025)

These bugs affect delegation in the current Coday runtime and are relevant context for designing AgentOS delegation:

- **#623** — `AI__redirect` inside a delegation lands on the parent thread, not the sub-thread
- **#619** — delegation blocks when a sub-agent asks an interactive question (no user is listening on the sub-thread SSE)
- **#582** — delegation is blocked in `prompt` context

The root cause of #619 is the mismatch between the interactive-question model (which assumes a user is present) and the delegation model (which runs headless). AgentOS should treat interactive questions in delegated/headless contexts as errors or timeouts, not blocking waits.

### Design constraints for AgentOS

When implementing delegation in AgentOS:

1. A delegated `CaseRuntime` must know whether it is running interactively (user present on SSE) or headlessly (spawned by another agent). Interactive questions should be refused or time-out in headless mode.
2. Sub-case events need a parent-case link if the UI is to surface them inline.
3. Async delegation requires the parent to store the sub-case `id` as part of its event history so it can reconnect later.
4. The `stackDepth` concept (max nesting of delegations) must be enforced to prevent infinite delegation chains.

## Agent-Centric vs Project-Centric Scoping

The current AgentOS model is namespace-scoped: a `Case` belongs to a `Namespace`, and agents are discovered within that namespace. This is essentially project-centric.

The target is flexible agent scoping across multiple levels:
- **User scope** — an agent that knows a specific user across all their projects (twin concept)
- **Namespace/project scope** — current model, agents siloed per project
- **Case/thread scope** — agents that only exist for the duration of a single case

Cross-project agent access (a user-scoped agent that can reach into multiple namespaces) and namespace hierarchies (worktrees as sub-namespaces inheriting from a parent) are open design questions. No architectural decision has been taken yet; both models should remain possible as AgentOS evolves.
