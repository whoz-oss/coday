# Collaborative case chat

The existing message endpoint supports explicit, opt-in team discussion without changing old agent clients.

## Message modes

`POST /api/cases/{caseId}/messages`

```json
{"content":"Let's agree on the proposal first.","mode":"HUMAN"}
```

HUMAN persists the authenticated user's MessageEvent and broadcasts it on the case event stream. It does not resolve prompts, select an agent, launch a run, generate an AI title, answer a pending agent question, or interrupt an active agent. Mentions are ordinary text.

```json
{"content":"@researcher Summarize our discussion.","mode":"AGENT","agentName":"researcher"}
```

AGENT requires one exact deployed agent name accessible to the authenticated sender in the case namespace. Invalid/unavailable/ambiguous targets produce HTTP 400, without fallback to a default agent. A leading mention, if present, must agree with agentName. Selection uses the validated target, not legacy fuzzy mention resolution. The normal execution loop receives all case discussion events, including passive human messages. Active or admitted turns produce HTTP 409 rather than losing a second invocation silently.

`agentName` is allowed only with AGENT. `answerToEventId` is allowed only without an explicit mode: normal question/answer interactions retain their existing behavior. Omit mode for legacy agent turns, prompt commands and question answers. Old clients are unchanged.

## Live team subscriptions

Open `/api/cases/{caseId}/events?includePreviousEvents=true&collaborative=true`.

The collaborative opt-in restores/keeps a runtime for an inactive persisted case without executing an agent. Otherwise a reader who connected before the first team message, or after a server restart, would only receive history followed by stream closure. The default remains legacy inactive-case replay behavior. SSE channel stays `case-event`, with CaseEvent subtype in `data.type`.

## Persistence and permissions

Passive MessageEvents carry `startsAgentTurn:false`. Historical events default to true. The marker survives Neo4j persistence and soft deletion. Runtime turn boundaries and execution authorization use invoking messages only, so another participant's mid-run discussion cannot steal the invoking user's identity or hide the active selection. Passive events remain visible in the discussion and agent context.

Posting requires existing Case WRITE permission; case reads and SSE require existing Case READ permission. No namespace-wide membership or new grants are inferred. The author comes from the server session, never request JSON.

## Compatibility limits

Collaboration is request/SSE opt-in rather than a persistent case-wide flag. A legacy client can still explicitly use its historical auto-agent path on the same case. Multi-server cross-process SSE distribution is not added; the existing runtime/event architecture remains process-local. Agent invocation does not expand `/prompt` aliases in explicit AGENT mode; use the legacy path for prompt workflows.
