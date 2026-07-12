# A2A (Agent2Agent) — Prototype

This document describes the **prototype A2A protocol exposure** shipped in
`io.whozoss.agentos.a2a`. It is **not production-ready**: it has no
authentication, no push notifications, and several protocol shortcuts. It exists
to prove interoperability with A2A-compliant clients on a local dev setup, and
to serve as the foundation for a full implementation.

- Spec reference: <https://a2a-protocol.org/latest/specification/> (v1.0.0).
- Prototype scope:
  - **JSON-RPC binding** (spec §9): Agent Card discovery + `message/send`,
    `message/stream`, `tasks/get`, `tasks/cancel`
  - **HTTP+JSON/REST binding** (spec §11): `POST message:send`,
    `POST message:stream` (SSE), `GET tasks/{id}` — used by
    [promptfoo's A2A provider](https://www.promptfoo.dev/docs/providers/a2a/)
    and other REST-first tools.
  Both bindings are backed by the same `A2AService` and produce equivalent
  behavior against the underlying case flow.

## 1. URLs

Every AgentOS agent (that is `enabled = true` in its namespace) gets its own
A2A URL, rooted at:

```
/api/a2a/{namespaceId}/{agentName}
```

- `namespaceId` — UUID of the namespace (no slug/name lookup in the prototype).
- `agentName` — case-insensitive match against `AgentConfig.name`.

| Endpoint | Method | Binding | Purpose |
|---|---|---|---|
| `/{ns}/{agent}/.well-known/agent-card.json` | `GET` | — | Agent Card (discovery, spec §8) |
| `/{ns}/{agent}` | `POST` | JSON-RPC | JSON-RPC methods: `message/send`, `tasks/get`, `tasks/cancel` |
| `/{ns}/{agent}/stream` | `POST` | JSON-RPC | SSE stream for `message/stream` (§9.4.2) |
| `/{ns}/{agent}/message:send` | `POST` | HTTP+JSON | REST `SendMessage` (§11.3.1) |
| `/{ns}/{agent}/message:stream` | `POST` | HTTP+JSON | REST `SendStreamingMessage` (§11.7) — SSE |
| `/{ns}/{agent}/tasks/{id}` | `GET` | HTTP+JSON | REST `GetTask` (§11.3.2) |

The prototype runs on the AgentOS Spring Boot port (default `8124`), so a full
example URL is:

```
http://localhost:8124/api/a2a/9b1e…/researcher/.well-known/agent-card.json
```

The Coday Node proxy at `apps/server/src/server.ts` currently only forwards
`/api/agentos/*` to AgentOS — it does **not** forward `/api/a2a/*`. That is
intentional: A2A callers are external agents, not the Coday web UI, and should
address AgentOS directly.

## 2. Conceptual mapping

| A2A concept | AgentOS concept |
|---|---|
| A2A Agent (URL-addressable) | `AgentConfig` resolved by `(namespaceId, name)` |
| Task | `Case` (1 task = 1 case) |
| Task `id` | `Case.metadata.id` (UUID, stringified) |
| Task `contextId` | Same as `taskId` (v1 heuristic — see limitations) |
| Message (`role: user`) | `MessageEvent` with `Actor(role=USER)` on the case |
| Message (`role: agent`) | `MessageEvent` with `Actor(role=AGENT)` — exposed as `TaskArtifactUpdateEvent` |
| `TaskStatusUpdateEvent` | Derived from `CaseStatusEvent`, `AgentRunningEvent`, `QuestionEvent`, `ErrorEvent` |
| `TaskState` | `A2ATaskState` (see mapping below) |

### 2.1. TaskState mapping

Each binding has its own mapper because the wire-level value casing differs
between them (spec §5.5): JSON-RPC uses `"submitted"`, REST uses
`"TASK_STATE_SUBMITTED"`.

| `CaseStatus` | JSON-RPC (`CaseEventMapper`) | REST (`RestBindingMapper`) |
|---|---|---|
| `PENDING` | `submitted` | `TASK_STATE_SUBMITTED` |
| `RUNNING` | `working` | `TASK_STATE_WORKING` |
| `IDLE` | `input-required` | `TASK_STATE_COMPLETED` ⚠ |
| `KILLED` | `canceled` | `TASK_STATE_CANCELED` |
| `ERROR` | `failed` | `TASK_STATE_FAILED` |

**Known imprecision** — the two mappers disagree on `IDLE`:

AgentOS `IDLE` means "agent turn ended, awaiting input" _and_ "task is done" —
the runtime doesn't distinguish them. The two bindings make different
tradeoffs:

- JSON-RPC surfaces `input-required`, which is semantically closer to the
  ambiguous truth but requires the client to know it should send a follow-up
  message to make progress.
- REST surfaces `completed`, which is what most eval / poll-based clients
  (promptfoo included) expect to close the poll loop and extract artifacts.
  A client sending a follow-up message on the same taskId will move the case
  back to `RUNNING` transparently.

A proper implementation would emit an explicit "agent finished its work"
signal (e.g. an `AgentDoneEvent`) so both mappers can pick `completed` vs
`input-required` deterministically. See §5.2 TODO.

### 2.2. Agent targeting via `@mention`

A2A calls a URL that is specific to an agent, but AgentOS `Case`s are
agent-agnostic at creation time — the agent is picked per user message by
`CaseServiceImpl.selectAgent()` (mention regex, then continuity, then namespace
default).

The prototype forces the correct agent by **prefixing the outgoing user
message with `@AgentName`** on the very first turn (`case.status == PENDING`).
This piggybacks on the existing mention path — zero core changes.

Consequences:
- The `@AgentName` prefix is visible in the case history (cosmetic issue).
- Follow-up user messages on the same task do **not** re-inject the mention,
  which is fine as long as the agent stays selected via the "last selected"
  continuity rule.

Alternative (not implemented): add a `forceAgentName: String?` parameter to
`CaseService.addMessage()`, and emit an explicit `AgentSelectedEvent` before
the message. Cleaner semantically, requires editing the case flow.

## 3. Flows

### 3.1. Non-streaming send (`message/send`)

```
Client                                     A2AController        A2AService       CaseService
  │                                              │                   │                │
  │ POST /api/a2a/{ns}/{agent}                   │                   │                │
  │ {jsonrpc, method:"message/send", params}     │                   │                │
  ├─────────────────────────────────────────────►│                   │                │
  │                                              │ resolveAgent()    │                │
  │                                              ├──────────────────►│                │
  │                                              │                   │ create(Case)   │
  │                                              │                   ├───────────────►│
  │                                              │                   │◄──────────────┤
  │                                              │                   │ addMessage(   │
  │                                              │                   │  "@Agent " +  │
  │                                              │                   │  userText)    │
  │                                              │                   ├───────────────►│ (async run)
  │                                              │                   │◄──────────────┤
  │                                              │◄──────────────────┤                │
  │ JSON-RPC result = Task{id, status:submitted} │                   │                │
  │◄─────────────────────────────────────────────┤                   │                │
```

The client then polls `tasks/get` or opens `message/stream` to get updates.

### 3.2. Streaming (`message/stream`)

Same initial flow, but the HTTP response is `text/event-stream`. Each SSE frame
carries a JSON-RPC 2.0 envelope whose `result` is one of:

- The initial `Task` snapshot (first frame).
- A `TaskStatusUpdateEvent` (state change).
- A `TaskArtifactUpdateEvent` (agent output).

The stream closes when a `TaskStatusUpdateEvent` with `final: true` is sent:
either a terminal state (`completed`/`failed`/`canceled`) or an
`input-required` (which per spec §3.5.2 also closes the stream — the client
reopens on the next turn).

Heartbeat: every 15 s, a `keep-alive` SSE comment is written so client
disconnects are detected during `IDLE`.

## 4. Deliberate omissions in v1

These items are **spec-compliant to omit** (features declared unsupported in
the Agent Card) but a full A2A server would eventually need them:

| Feature | Spec ref | State |
|---|---|---|
| Authentication (API key, OAuth2, mTLS…) | §7, §4.5 | None. Anyone can call anything. |
| `tasks/list` | §3.1.4, §9.4.4 | Not implemented. |
| `tasks/resubscribe` | §9.4.6 | Not implemented — clients must reopen `message/stream`. |
| Push notifications | §3.5.3, §9.4.7 | Declared `false` in Agent Card, no config endpoints. |
| `agent/getAuthenticatedExtendedCard` | §3.1.11 | Not implemented (public card only). |
| gRPC binding | §10 | Not exposed. Only JSON-RPC. |
| HTTP+JSON/REST binding | §11 | Not exposed. Only JSON-RPC. |
| Agent Card signing (JWS) | §8.4 | Not implemented. |
| File / rich Data parts round-trip | §4.1.6 | Text only. Image content in AgentOS is dropped to a placeholder text. |
| Distinct `contextId` (multi-task grouping) | §3.4 | Prototype uses `contextId == taskId`. |
| Full task `history` field on `tasks/get` | §3.2.4 | Returned as `null`. |
| Artifact accumulation semantics (`append`, `lastChunk`) | §4.2.2 | Each agent message = one standalone artifact (`append=false`, `lastChunk=true`). No chunking. |

## 5. TODO — what to build for a production implementation

Ordered by dependency, roughly:

### 5.1. Authentication & authorization

- Choose a scheme (API keys per namespace or per agent is the leanest;
  the codebase already has `AgentOsAuthenticationFilter` which resolves
  `X-External-User-Id` — API keys could plug into the same filter).
- Populate the Agent Card `securitySchemes` + `security` fields
  (`APIKeySecurityScheme`, spec §4.5.2). Currently the card omits them,
  which per spec §7 implies no auth.
- Enforce per-agent access (a caller for agent A shouldn't see task events
  for agent B in the same namespace).
- Rate-limiting per key.

### 5.2. Explicit "task completed" semantics

Right now, agent completion is indistinguishable from "waiting for user":
both are `CaseStatus.IDLE`. To emit A2A `completed` correctly:

- Option A (minimal): treat `IDLE` after an `AgentFinishedEvent` with no
  outstanding `QuestionEvent` as `completed`, and only revert to
  `input-required` if the case is subsequently addressed. Requires state
  tracking in `CaseEventMapper` (currently stateless).
- Option B (proper): introduce an `AgentDoneEvent` in the SDK, emitted by
  the runtime when the LLM produces a final answer with no tool call
  pending. Requires touching `AgentAdvanced`.

### 5.3. `contextId` semantics

A2A `contextId` is meant to group related tasks (spec §3.4.1). AgentOS's
closest concept is `parentCaseId` for delegation. The prototype uses
`contextId == taskId` which flattens the notion.

To align:
- Accept an incoming `contextId` on `message/send` and use it to link the
  new case to an existing case (populate `Case.parentCaseId`).
- On `tasks/get`, expose the delegation tree if any.

### 5.4. `tasks/list`

Spec §3.1.4 / §9.4.4. Requires:
- Filter by `contextId`, `state`, cursor pagination.
- Namespace-scoped query on `CaseRepository` (already partially available
  via `findByParent(namespaceId)`), reformatted to the A2A response shape.

### 5.5. Push notifications

Spec §3.5.3. Requires:
- A `PushNotificationConfig` persistence store keyed by `(caseId, url)`.
- Outbound HTTP webhook worker that POSTs `TaskStatusUpdateEvent` payloads
  as they occur.
- Signing (JWS or a shared secret) so the receiver can verify authenticity.
- The 4 methods `tasks/pushNotificationConfig/{set,get,list,delete}`.

### 5.6. Rich content

- Wire `A2APart.FilePart` into `MessageContent` (needs an `Image`- or
  `File`-aware `MessageContent` sibling; today the only non-text option is
  `MessageContent.Image` which is opaque base64).
- Support `outputModes` negotiation in the Agent Card (spec §3.2.2).

### 5.7. Agent Card completeness & signing

- Populate `provider`, `documentationUrl`, `iconUrl`, real `skills`
  (one per agent capability, derived from `AgentConfig.instructions` or a
  new `AgentConfig.skills` field).
- Optionally sign the card (§8.4) so consumers can verify it wasn't
  tampered with in transit.

### 5.8. Robustness

- `A2AController` currently builds the Agent Card URL from `Host` +
  `X-Forwarded-Proto` headers; behind a reverse proxy this needs the
  Spring Boot `forward-headers-strategy=framework` setting or an explicit
  `A2A_PUBLIC_BASE_URL` env var.
- Error mapping: today only a handful of exceptions get mapped to
  A2A-specific JSON-RPC codes (`TASK_NOT_FOUND`, `TASK_NOT_CANCELABLE`).
  Extend to `ContentTypeNotSupportedError`, `InvalidAgentResponseError`,
  etc. (spec §9.5).
- SSE reconnect: expose `Last-Event-Id` handling so a dropped stream can
  be replayed from the last delivered event (the underlying
  `caseEventService.findByParent` is already suitable).

## 6. Testing manually

Ensure at least one namespace and one enabled agent exist. Start AgentOS
(`nx run agentos:start` or the equivalent gradle task).

### JSON-RPC binding

```bash
# 1. Discovery
curl http://localhost:8124/api/a2a/<namespaceId>/<agentName>/.well-known/agent-card.json

# 2. Send a message (non-streaming)
curl -X POST http://localhost:8124/api/a2a/<namespaceId>/<agentName> \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "message/send",
    "params": {
      "message": {
        "role": "user",
        "messageId": "msg-1",
        "parts": [{ "kind": "text", "text": "Hello, who are you?" }]
      }
    }
  }'

# 3. Stream (SSE)
curl -N -X POST http://localhost:8124/api/a2a/<namespaceId>/<agentName>/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": "2",
    "method": "message/stream",
    "params": {
      "message": {
        "role": "user",
        "messageId": "msg-2",
        "parts": [{ "kind": "text", "text": "Explain A2A in one line." }]
      }
    }
  }'

# 4. Poll a task
curl -X POST http://localhost:8124/api/a2a/<namespaceId>/<agentName> \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0", "id": "3", "method": "tasks/get",
    "params": { "id": "<taskId returned above>" }
  }'

# 5. Cancel
curl -X POST http://localhost:8124/api/a2a/<namespaceId>/<agentName> \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0", "id": "4", "method": "tasks/cancel",
    "params": { "id": "<taskId>" }
  }'
```

### HTTP+JSON/REST binding

```bash
# 1. Send a message (non-streaming) — returns a Task
curl -X POST http://localhost:8124/api/a2a/<namespaceId>/<agentName>/message:send \
  -H 'Content-Type: application/json' \
  -d '{
    "request": {
      "role": "ROLE_USER",
      "messageId": "msg-1",
      "parts": [{ "text": "Hello, who are you?" }]
    }
  }'

# 2. Poll the task until it reaches TASK_STATE_COMPLETED
curl http://localhost:8124/api/a2a/<namespaceId>/<agentName>/tasks/<taskId>

# 3. Stream (SSE) — each frame is a StreamResponse envelope
curl -N -X POST http://localhost:8124/api/a2a/<namespaceId>/<agentName>/message:stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "request": {
      "role": "ROLE_USER",
      "messageId": "msg-2",
      "parts": [{ "text": "Count from 1 to 5." }]
    }
  }'
```

## 7. File layout

```
agentos-service/src/main/kotlin/io/whozoss/agentos/a2a/
├── A2AController.kt          # JSON-RPC endpoints: agent card + JSON-RPC + SSE
├── A2ARestController.kt      # REST endpoints: message:send, message:stream, tasks/{id}
├── A2AJsonRpcHandler.kt      # JSON-RPC method dispatch (message/send, tasks/get, tasks/cancel)
├── A2AService.kt             # Shared: agent resolution + case orchestration + snapshots
├── dto/
│   ├── A2AMessage.kt         # (JSON-RPC) Message DTO
│   ├── A2AParams.kt          # (JSON-RPC) SendMessageParams, TaskQueryParams, TaskIdParams
│   ├── A2AParts.kt           # (JSON-RPC) Part (Text/File/Data) sealed hierarchy
│   ├── A2ATask.kt            # (JSON-RPC) Task, TaskStatus, TaskState, streaming events
│   ├── A2ARestDto.kt         # (REST) Rest{Task,Message,Part,...} — proto-style constants
│   ├── AgentCard.kt          # AgentCard, AgentCapabilities, AgentInterface, AgentSkill
│   └── JsonRpc.kt            # JsonRpcRequest/Response/Error + standard codes
└── mapping/
    ├── CaseEventMapper.kt    # (JSON-RPC) CaseEvent -> TaskStatus/ArtifactUpdate
    └── RestBindingMapper.kt  # (REST) CaseEvent -> RestTaskStatus/ArtifactUpdateEvent

examples/promptfoo-a2a/       # Example: testing an agent with promptfoo's `a2a` provider
├── README.md
└── promptfooconfig.yaml
```

No changes were made to existing case-flow, agent-config, or namespace code.
The prototype is fully additive.

## 8. Testing with promptfoo

The [promptfoo A2A provider](https://www.promptfoo.dev/docs/providers/a2a/)
consumes the HTTP+JSON binding. A ready-to-run config is provided at
`agentos/examples/promptfoo-a2a/` — see that folder's README for setup.

Minimal example:

```yaml
providers:
  - id: a2a:http://localhost:8124/api/a2a/<NAMESPACE_ID>/<AGENT_NAME>
    config:
      mode: send   # POST /message:send then poll GET /tasks/{id}
      polling: { intervalMs: 1000, timeoutMs: 300000 }

prompts: ['{{prompt}}']
tests:
  - vars: { prompt: 'Hello, who are you?' }
    assert: [{ type: not-empty }]
```
