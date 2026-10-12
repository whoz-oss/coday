# Coday → AgentOS Migration Status

Feature-parity register for the migration from the Coday TypeScript runtime
(`libs/`, `apps/server`) to the AgentOS Kotlin backend (`agentos/`).

**Status legend**

| | Meaning |
|---|---|
| ✅ | Implemented in AgentOS |
| ⚠️ | Partially implemented, or implemented differently |
| ❌ | Not implemented |
| 🚫 | Deliberately not migrating |

**Confidence legend** — how the status was established.

| | Meaning |
|---|---|
| **H** | Read the implementing code directly |
| **M** | Located the owning subsystem; inferred from structure |
| **L** | Keyword search only — weakest evidence, see caveat at the bottom |

---

## 1. Core runtime

| Capability | Coday | AgentOS | Status | Conf. |
|---|---|---|---|---|
| Thread / case lifecycle | `ThreadCodayManager` | `CaseRuntime` + `CaseServiceImpl` | ✅ | H |
| Event streaming (SSE) | per-thread SSE | `CaseEventSseController` | ⚠️ | H |
| Agent hand-off / redirect | `AI__redirect` | `AgentInterrupt.Redirect` + `RedirectTool` | ✅ | H |
| Ask-the-user | `AI__queryUser` | `QueryUserTool` + `AgentInterrupt.AwaitAnswer` | ✅ | H |
| Delegation to sub-agents | `DELEGATE__delegate` | `DelegationTool` + `SubCaseManager` | ✅ | H |
| Iteration limits | handler loop guard | `agentos.limits.*` | ✅ | H |
| Token / cost accounting | thread `price` | `LlmUsage` + `CostCalculator` | ✅ | H |
| Run-cost human gate | — | `agentos.limits.run-cost-threshold` | ✅ | H |
| Webhook triggering | `webhook.routes.ts`, `WebhookService` | — | ❌ | H |
| Voice input / transcription | Web Speech API (client) | `transcription: none` | 🚫 | H |
| Per-user SSE subscription | — | — | ❌ | H |

### Notes

**SSE — ⚠️ case-scoped, not user-scoped.** `GET /api/cases/{caseId}/events` is a
per-case stream. It is more robust than Coday's in several respects: a stable
`case-event` channel with a `type` discriminant in the payload, durable history replay
before live events, de-duplication by stable event id, a bounded per-connection live
buffer that closes the connection for a clean replay rather than silently dropping
events, and heartbeat frames used as the disconnect signal. What is missing is a
subscription model that fans events from cases to *user* sessions — required for the
`ActorSelectedEvent` target design. See `multi-user-and-delegation.md`.

**Delegation — ✅ and it closes known Coday bugs rather than porting them.**
Parallel fan-out, per-delegation timeout budgets, resume by `subCaseId`, and an
`allowedAgents` allowlist. Coday **#619** (delegation blocks when a sub-agent asks a
question) is structurally fixed: the pending question is returned to the parent as
`pendingQuestion` + `options` to relay or resolve, instead of blocking on an SSE nobody
is listening to. Human cost-confirmation time is excluded from the timeout budget. A
timed-out delegation does not cancel siblings that already succeeded.

**Token / cost — ✅ and beyond parity.** Counts are provider-normalised so
cross-provider totals mean something (Anthropic `inputTokens` already excludes cache;
OpenAI is `promptTokens − cachedTokens`). `estimatedCostUsd` is nullable where **null
means "pricing unknown", not zero**, and null propagates through aggregation so one
unpriced model taints the total rather than silently under-reporting it. Gated behind
`agentos.usage.enabled`, which defaults to `false` in every profile.

**Voice — 🚫 recommend not migrating.** Both desktop apps already hide `.voice-btn`
because the Web Speech API does not work in Electron. Confirm with product before
scheduling any work.

**Webhooks — ❌ genuinely absent.** `webhook` appears in the Kotlin tree only in KDoc
prose and as a Slack `webhookUrl` config parameter. No controller, no service. Note
that the headless-agent design already anticipates it: `QUERY_USER` can be opted out
per-agent via an empty list, precisely so a webhook-triggered agent does not block on
an unanswerable question.

---

## 2. Integrations

Authoritative source: the `libs/integrations/` directory. **11 integrations, not 7** —
the widely-repeated "7 missing integrations" figure counts only the unported business
connectors and omits the four that already have Kotlin equivalents.

| Coday integration | AgentOS | Status | Conf. |
|---|---|---|---|
| `file` | `agentos-file-plugin` | ✅ | H |
| `git` | `agentos-git-plugin`, `agentos-git` | ✅ | H |
| `http` | `agentos-http-plugin` (`HTTP_API`) | ✅ | H |
| `ai` | core chat/model layer | ✅ | M |
| `jira` | — | ❌ | H |
| `confluence` | — | ❌ | H |
| `slack` | — | ❌ | H |
| `gitlab` | — | ❌ | H |
| `basecamp` | — | ❌ | H |
| `excel` | — | ❌ | H |
| `zendesk-articles` | — | ❌ | H |

AgentOS additionally has `agentos-bash-plugin`, `agentos-datetime-plugin`,
`agentos-mcp-plugin` (`MCP_STDIO` / `MCP_HTTP`), `agentos-tmux-plugin` and
`agentos-plugins-filesystem`.

### The seven ❌ rows are mis-framed as a porting task

They are **not** seven Kotlin plugins waiting to be written. AgentOS has a generic
`HTTP_API` plugin that generates one tool per curated OpenAPI operation, with URL
policy, auth-setting binding, per-namespace concurrency limiting, catalogue caching and
failure recording.

A conversion pipeline already exists: `scripts/utils/http-integration-to-openapi.ts`
converts a Coday `HttpIntegrationConfig` into an OpenAPI 3.0.3 document plus an
`IntegrationConfig` skeleton, preserving per-operation `keepPaths` response shaping and
`responseFormat`, and deriving `allowMutations` from the presence of non-GET endpoints.
Its spec asserts byte-equality against
`agentos-http-plugin/src/test/resources/openapi/converted-my-calendar.yaml` — the same
fixture the plugin's own round-trip test reads.

**The real question is per-integration**: which of the seven are expressible as
OpenAPI + response shaping (cheap, already tooled), and which need genuine custom tool
logic (expensive)? These differ by roughly an order of magnitude in cost. A single
aggregate row erases that distinction, which is exactly how the "7 missing plugins"
framing misleads.

**This triage has not been done. It is the highest-value next step.** Candidates to
examine first for resisting an OpenAPI framing: `slack` (socket-mode / event
subscriptions) and `excel` (binary document manipulation, not an HTTP API at all).

See `http-api-integration.md`.

---

## 3. AgentOS capabilities with no Coday equivalent

These are not migration items; they are reasons the migration is worth doing. Listed so
the register is not read as a one-directional deficit.

| Capability | Where |
|---|---|
| Namespace / user / platform config scoping, 4-tier merge | `user-level-overlays.md` |
| Neo4j permissions graph, soft-delete via labels | `neo4j-persistence.md`, `adr/001` |
| OAuth credential flow | `OAuthFlowService`, `agentos.oauth.*` |
| Parameter encryption (no defaults — service will not boot without them) | `AGENTOS_ENCRYPTION_KEY` / `_SALT` |
| Tool confirmation gates | `StandardTool.getConfirmationMode` |
| Micrometer / Datadog metrics | `metrics.md` |
| Prompt scheduler with UTC execution windows | `scheduled-prompt.md` |
| Git workspaces | `git-workspaces.md` |
| Plugin system (PF4J) | `plugin-system.md` |
| File exchange with image/document tooling | `file-exchange.md` |

---

## 4. Open design questions

Upstream of implementation — these should not be settled implicitly by sequencing
decisions.

1. **Agent scoping model.** AgentOS is namespace-scoped (project-centric). The target
   allows user-scoped agents (the "twin" concept), namespace-scoped, and case-scoped.
   Cross-project agent access and namespace hierarchies (worktrees as sub-namespaces)
   are explicitly undecided.
2. **`ActorSelectedEvent`.** Generalising agent selection to actor selection (user /
   group / agent) is the prerequisite for per-user notification and therefore for
   per-user SSE.
3. **Sync vs async delegation.** `DelegationTool` is synchronous. Coday also supports
   fire-and-forget. Async means parent and sub-case SSE flows are independent, which is
   an unsolved UX problem on reconnect.
4. **`stackDepth` enforcement.** Listed as a design constraint in
   `multi-user-and-delegation.md`; not verified as implemented in `DelegationTool`.

---

## 5. Maintenance

**`multi-user-and-delegation.md` is stale.** It states that delegation is not
implemented and lists `SyncDelegation` as planned. `DelegationTool` has since landed.
Its *design constraints* and *Coday bug post-mortems* remain valuable; its status claims
do not. It should be updated or explicitly marked historical.

**Coday issues #619 and #623** are candidates for closing as superseded rather than
fixed, if the AgentOS implementation is the forward path.

### Caveat on negative findings

An earlier revision of this register recorded token accounting as absent. It was fully
implemented — the search term was `tokenUsage`; the code says `LlmUsage`. Absence of a
keyword is weak evidence of absence of a feature.

When marking something ❌, search at least two plausible naming conventions and locate
the subsystem that *would* own the feature. Rows marked **L** have not met that bar.
There are currently none, but any row added by keyword search alone should be marked
**L** rather than silently promoted.

Reports from delegated agents on this migration have been unreliable in **both**
directions — claiming implemented features missing, and missing features complete.
Verify structural claims against code before acting on them.
