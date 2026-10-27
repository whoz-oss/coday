# Config Agent

An agent that names the built-in `AGENTOS_CONFIG` integration can read the AgentOS configuration the
user can see and propose changes to it: agents, skills, prompts, integrations, auth settings and
agent deployment. The agent never writes. The user reviews the exact plan in a card and clicks
Apply; the server then applies it with that user's rights and tells the agent what happened.

Issue: #1477.

## Enabling it

`AGENTOS_CONFIG` is a built-in integration, like the file exchange: there is no `IntegrationConfig`
instance. An agent enables it with a key in its `integrations` map:

| Value | Tools granted |
|---|---|
| `AGENTOS_CONFIG: null` | `AGENTOS_CONFIG__read` and `AGENTOS_CONFIG__propose` |
| `AGENTOS_CONFIG: [read]` | `AGENTOS_CONFIG__read` only |
| `AGENTOS_CONFIG: []` | none (explicit opt-out) |
| key absent | none |

It is never granted by default (`enabledByDefault = false`, no platform property). In the agent
form it appears under "Built-in integrations". Enabling it requires AgentConfig WRITE on the agent,
like any other change to the agent.

The form warns when an agent has `AGENTOS_CONFIG` together with a network integration (`HTTP_API`,
`MCP_HTTP`) or sub-agents: reads do not go through the Apply click, so such an agent could send what
it read elsewhere. Prefer a dedicated configuration agent without them.

## Flow

```
agent --AGENTOS_CONFIG__read----> configuration the user can see, secrets masked
agent --AGENTOS_CONFIG__propose-> validation, order, hash; plan stored in the ToolResponseEvent
                                  (structuredOutput); run suspends on a question with that id
card  --GET  /api/cases/{c}/config-changes/{q}         plan + live status, read with the viewer's rights
user  --POST /api/cases/{c}/config-changes/{q}/apply   {expectedPlanHash, secrets}
server   applies the plan in this request through the *Api beans, then posts the answer
agent    resumes with the per-operation report
```

## Rights

The user is responsible for what they apply. There is no ceiling and no rule beyond the REST API:
the agent may propose anything, and each write is accepted or refused by the REST controllers with
the identity of the user who clicked.

- **Writes** run inside the HTTP request of the Apply click, through the public `*Api` interfaces
  (`AgentConfigApi`, `SkillApi`, `PromptApi`, `IntegrationConfigApi`, `AuthSettingApi`, and
  `NamespaceController` for deployment). Their only implementation is the controller's
  method-security proxy, so `@PreAuthorize` and the inline guards apply exactly as for the browser.
  `ApiBeanBridgeAuthorizationSpec` proves it. This goes through the `ConfigWriter` port; phase 2
  swaps it, entity by entity, for the service-level permission methods (`*ForCaller`, #1492).
- **Only the user the plan was proposed to** may apply or reject it (403 `NOT_RECIPIENT`).
- **The click is bound to the displayed plan**: Apply carries the plan hash, and anything else is
  refused (409 `PLAN_HASH_MISMATCH`).
- **Secrets** are never written by the agent: the plan declares their paths and the user types the
  values in the card. Only real secret fields qualify (`/apiKey`, `/token`, `/password`,
  `/clientSecret` of an auth setting, paths under `/parameters/` of an integration), so a typed key
  never lands in a plain field. A proposal containing `****` or a value at a secret path is refused.
- **Bean Validation** runs on every DTO before the in-process call, since `@Valid` only applies to
  HTTP requests: a value the REST API would refuse with 400 is reported `INVALID`.
- **Reads** (tool and card) use the same rules as the REST read endpoints, owner fallback included
  (`AgentOsPermissionEvaluator.hasPermission(userId, ...)`). They are bounded to the platform, the
  current namespace and the caller's own entities, because what the tool reads is stored in the case
  history. Integration parameters are masked closed by default (only keys such as `baseUrl` are
  shown) and auth setting secrets are fully masked.

## Plans

`propose` takes operations on `AGENT`, `SKILL`, `PROMPT`, `INTEGRATION` and `AUTH_SETTING`:

- `CREATE` carries a `scope` (`namespaceId`: `"current"` or null for platform; `userId`: `"me"` or
  null, and always null for agents and skills, which have no personal scope) and a `value` with the
  REST DTO fields. `{"$op": "<ref>"}` stands for the id an
  earlier operation of the plan creates. A new agent needs `deployTo: NAMESPACE | NONE`.
- `UPDATE` targets an `id` and lists `changes` by JSON Pointer path (`/instructions`,
  `/integrations/MY_API`), merged by the server onto the full DTO it re-reads. A path cannot go
  through a list: the whole list is replaced (`/content`, `/subAgents`).
- `ENABLE`, `DISABLE`, `DEPLOY`, `UNDEPLOY` (agents only) target an `id`.

Validation only anticipates what the REST API would refuse (immutable fields, prompt attached after
creation, unknown target) or what would make the plan ambiguous (unknown or cyclic reference). The
order is a topological sort of the references, ties broken by kind: auth settings, integrations,
skills, agent creation, agent update, prompts, enable/disable, deployment.

Limits (`ConfigAgentLimits`): 20 operations per plan, plans expire after 24 hours.

## Applying

Each operation's status is recomputed from the real state at every read: `PENDING`, `APPLIED`,
`ALREADY_APPLIED`, `CONFLICT` (the value changed since the plan was made) or `NOT_VISIBLE`. Apply
walks the order, skips what is already applied, writes what is pending, and stops at the first
refusal or conflict. Nothing is rolled back. A claim per question serializes apply and reject
(single instance, like the case runtime registry).

Whatever the outcome, the server posts the report as the answer to the question, which closes the
plan and resumes the agent. To retry after a partial failure, the agent proposes again: the new plan
converges, since what the first one already wrote shows as `APPLIED` or `ALREADY_APPLIED`. A newer
plan in the same case marks the older ones `SUPERSEDED` for good. If the answer cannot be posted
(the case was closed meanwhile), the report says `answerPosted: false` and the plan stays open, so
applying it again re-sends it. The answer text carries only fixed templates and codes, never
parameter values, since it is replayed to the LLM.

## Limits

- A proposal made in a delegated sub-case shows its card in the sub-case only.
- Clients without the card (Slack, older desktop) show the question text; a text reply closes the plan.
- A member cannot create a personal `HTTP_API` integration (refused in user scopes since #1314).
  The supported path is a shared integration created by an admin plus the member's own `API_KEY`
  auth setting in user x namespace scope, which wins at resolution.
- Group deployment, AI providers and AI models are not covered yet.
