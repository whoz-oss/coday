# Config Agent

An agent that names the built-in `AGENTOS_CONFIG` integration can read the AgentOS configuration the
user can see, diagnose a namespace, and propose changes: agents, skills, prompts, integrations, auth
settings, agent deployment, namespace settings, and new namespaces with their configuration. The
agent never writes. The user reviews the exact plan in a card and clicks Apply; the server then
applies it with that user's rights and tells the agent what happened.

The tools are meant to be the agent's only way to configure AgentOS: their description tells it
never to go through the REST API, a shell or a browser, and to say what is missing instead.

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

The form warns when an agent has `AGENTOS_CONFIG` together with an integration that can carry data
off the server (`HTTP_API`, `MCP_HTTP`, `MCP_STDIO`, `BASH`, `TMUX`, `GIT`) or with sub-agents: reads
do not go through the Apply click, so such an agent could send what it read elsewhere. Prefer a
dedicated configuration agent without them.

What the agent reads goes to the LLM provider and into the case history. Secrets are masked (auth
setting credentials, and every integration parameter but the keys that only locate a service), but
free text is returned in full: an agent's instructions, a prompt, a skill's body, a description. A
credential pasted into one of them is read like any other text.

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

The user is responsible for what they apply. There is no ceiling on rights: each write is accepted
or refused by the REST controllers with the identity of the user who clicked. The planner only
refuses what the tool could not show or apply faithfully: a scope outside the current namespace, the
namespaces the user administers and a namespace the plan creates, a personal scope for agents and
skills (they have none), a secret outside a credential field, a path through a list, an agent
created without an explicit `enabled`.

- **Writes** run inside the HTTP request of the Apply click, through the public `*Api` interfaces
  (`AgentConfigApi`, `SkillApi`, `PromptApi`, `IntegrationConfigApi`, `AuthSettingApi`,
  `NamespaceApi`, and `NamespaceController` for deployment). Their only implementation is the controller's
  method-security proxy, so `@PreAuthorize` and the inline guards apply exactly as for the browser.
  `ApiBeanBridgeAuthorizationSpec` proves it. This goes through the `ConfigWriter` port; phase 2
  swaps it, entity by entity, for the service-level permission methods (`*ForCaller`, #1492).
- **Only the user the plan was proposed to** may apply or reject it (403 `NOT_RECIPIENT`).
- **The click is bound to the displayed plan**: Apply carries the plan hash, and anything else is
  refused (409 `PLAN_HASH_MISMATCH`).
- **Secrets** are never written by the agent: the plan declares their paths and the user types the
  values in the card. Only real secret fields qualify: the secret fields of the auth setting's own
  `authType` (`/password` for `BASIC_AUTH`, `/apiKey` for `API_KEY`…), paths under `/parameters/` of an
  integration. A typed key therefore never lands in a plain field nor in a field its type drops. A
  proposal is refused when it contains `****`, a value at a declared secret path, a value at a secret
  field of its auth setting, or an integration parameter named like a credential (`token`,
  `password`, `apiKey`, `clientSecret`…).
- **Bean Validation** runs on every DTO before the in-process call, since `@Valid` only applies to
  HTTP requests: a value the REST API would refuse with 400 is reported `INVALID`.
- **Reads** (tool and card) use the same rules as the REST read endpoints: a list follows the list
  endpoints, a single entity follows its `GET /{id}` guard, owner fallback included
  (`AgentOsPermissionEvaluator.hasPermission(userId, ...)`). They are bounded to the platform, the
  current namespace, the namespaces the user administers (Namespace WRITE, so all of them for a
  super-admin) and the caller's own entities, because what the tool reads is stored in the case
  history. Integration parameters are masked closed by default (only keys such as `baseUrl` are
  shown) and auth setting secrets are fully masked. To copy an integration faithfully the agent
  uses `copyFrom` (below) rather than reading its parameters.

## Plans

`propose` takes operations on `AGENT`, `SKILL`, `PROMPT`, `INTEGRATION`, `AUTH_SETTING` and
`NAMESPACE`:

- `CREATE` carries a `scope` (`namespaceId`: `"current"`, the id of a namespace the user
  administers, `{"$op": "<ref>"}` of a namespace the plan creates, or null for platform; `userId`:
  `"me"` or null, and always null for agents and skills, which have no personal scope) and a `value`
  with the REST DTO fields. `{"$op": "<ref>"}` stands for the id an earlier operation of the plan
  creates. A new agent needs an explicit `value.enabled` (the REST API creates it disabled
  otherwise, and nobody can use it) and `deployTo: NAMESPACE | NONE`; `NAMESPACE` deploys it on the
  namespace it is created in, or on the current one for a platform agent.
- `CREATE` of an `INTEGRATION` may name `copyFrom`, the id of an integration within the bounds: at
  apply time the server reads it through `IntegrationConfigApi.getById` with the rights of the user
  who clicks, drops its id and scope, and merges `value` over it (objects key by key), so the agent
  copies parameters it never sees and only overrides some (for example a `GIT_REPOSITORY`'s
  `serviceAuthSettingId`, set to `{"$op": ...}` of the auth setting created in the new namespace).
  The card says which integration is copied.
- A namespace's Git repository is an integration of type `GIT_REPOSITORY` (one per namespace,
  shared): the generic integration API applies the same validation as the namespace Git screen, so
  the tool creates or copies it like any other integration. Its parameters are listed in the tool's
  description, since it is not a tool integration type.
- The tool's description lists every auth setting type with its own fields and marks the secret
  ones, read from the `AuthSettingDto` subtypes (`ConfigAuthSettingTypes`), so a new type shows up
  without touching the tool.
- `CREATE` of a `NAMESPACE` has a `value` only (`name`, `description`, `defaultAgentName`, the
  other `NamespaceDto` fields); the REST API reserves it to super-admins, and the card warns when
  the new namespace names no default agent. `UPDATE` of a `NAMESPACE` changes its settings
  (`/defaultAgentName`, `/description`, …; `externalId` is kept as created), with Namespace WRITE.
- `UPDATE` targets an `id` and lists `changes` by JSON Pointer path (`/instructions`,
  `/integrations/MY_API`), merged by the server onto the full DTO it re-reads. A path cannot go
  through a list: the whole list is replaced (`/content`, `/subAgents`).
- `ENABLE`, `DISABLE`, `DEPLOY`, `UNDEPLOY` (agents only) target an `id`. `DEPLOY` and `UNDEPLOY`
  act on the current namespace, or on the namespace their `scope` names (one the user administers or
  one the plan creates).

Validation only anticipates what the REST API would refuse (immutable fields, prompt attached after
creation, unknown target) or what would make the plan ambiguous (unknown or cyclic reference). The
order is a topological sort of the references, ties broken by kind: namespace creation, auth
settings, integrations, skills, agent creation, agent update, prompts, enable/disable, deployment,
namespace update (so a namespace can name as default an agent the same plan creates).

### Setting up a new namespace

A plan can create a namespace and configure it in one card, for example to copy the setup of the
current namespace: `CREATE NAMESPACE` (ref `ns`), then the entities with
`scope.namespaceId: {"$op": "ns"}`, and `DEPLOY` of existing platform agents with the same scope.
Agents of the current namespace cannot be deployed elsewhere (the REST API refuses it): copy them
with `CREATE` instead.

The statuses of the operations that go into the new namespace are evaluated inside it. Like any
other entity, a namespace that already exists with the proposed name and values counts as created,
so proposing the plan again converges. Once it exists, the user administers it (the REST API grants
its creator ADMIN), so later corrections (default agent, enabling an agent, a missing integration)
are proposed from the same case, with `scope.namespaceId` or a target `id` in that namespace.

### Diagnosing a namespace

`read` with `kind: namespace` and an `id` returns the namespace's settings and its readiness for its
members: whether a message without `@mention` reaches an agent, resolved like the runtime
(`CaseServiceImpl.selectDefaultAgent`: the namespace's `defaultAgentName`, else
`agentos.defaults.agent-name`, matched as a name prefix among the enabled agents deployed on the
namespace), and if not the first condition that fails: `NOT_SET`, `NOT_FOUND`, `DISABLED` or
`NOT_DEPLOYED`, with the agents deployed on it. It speaks for members rather than for the reader:
a super-admin's own messages skip the deployment check, and an agent deployed on a user group only
answers that group. `namespaceId` points the other reads (agents, integrations, deployment,
capabilities) at a namespace the user administers. This is what the service logs would otherwise be
needed for when a namespace does not answer.

Limits (`ConfigAgentLimits`): 20 operations per plan, plans expire after 24 hours.

## Applying

Each operation's status is recomputed from the real state at every read: `PENDING`, `APPLIED`,
`ALREADY_APPLIED`, `CONFLICT` (the value changed since the plan was made) or `NOT_VISIBLE`. Apply
walks the order, skips what is already applied, writes what is pending, and stops at the first
refusal or conflict. Nothing is rolled back. A claim per question serializes apply and reject
(single instance, like the case runtime registry).

A value counts as stored the way the REST layer stores it (`ConfigValueMatch`): an object holds the
proposed keys (a copy brings the others), numbers compare by value (`10` and `10.0`), strings trimmed,
and an empty value (absent, null, blank) only matches an empty one, so an existing entity that lacks
a proposed value is a `CONFLICT`, never `ALREADY_APPLIED`. Declared secrets are left out of the
comparison, path by path. A secret cannot be read back, so an `UPDATE` that carries one stays
`PENDING`; applying it again writes the same value. For an auth setting, removing a field sends an
empty value, since its API reads a missing or null field as "keep".

The decision is `APPLIED` when every step succeeded, `FAILED` when nothing was written, and
`PARTIAL` otherwise, an entity created but not deployed included. A DTO the REST API cannot even
read (missing type, wrong field type) is reported `INVALID` with its reason.

Whatever the outcome, the server posts the report as the answer to the question, which closes the
plan and resumes the agent. To retry after a partial failure, the agent proposes again: the new plan
converges, since what the first one already wrote shows as `APPLIED` or `ALREADY_APPLIED`. A newer
plan in the same case marks the older ones `SUPERSEDED` for good. If the answer cannot be posted
(the case was closed meanwhile), the report says `answerPosted: false` and the plan stays open, so
applying it again re-sends it. An expired plan can no longer be applied but can still be rejected,
which answers the agent. The answer text carries only fixed templates and codes, never parameter
values, since it is replayed to the LLM.

## Where the tools exist

The tools are granted in `AgentServiceImpl.resolveAgentDefinition` only:

- chat runs (AgentSimple and AgentAdvanced) get them;
- the definition preview lists them, and `propose` answers `NO_CASE` there, since it has no case;
- `GET /api/tools` and `POST /api/tools/invoke` never see them, since they resolve integration
  configs only;
- a LOOP workflow can only call them by naming them, and then takes the `AwaitAnswer` of `propose`
  as a failed step.

## Limits

- A proposal made in a delegated sub-case shows its card in the sub-case only.
- Clients without the card (Slack, older desktop) show the question text; a text reply closes the plan.
- A member cannot create a personal `HTTP_API` integration: user scopes refuse it, so that a member
  cannot point a shared credential at an API of their choosing. The supported path is a shared
  integration created by an admin plus the member's own `API_KEY` auth setting in user x namespace
  scope, which wins at resolution.
- Group deployment, AI providers and AI models are not covered yet, nor deleting a namespace.
- Auth settings are not copied: their secrets are typed again in the card.
- A namespace-shared auth setting is read and changed by id by super-admins only: `AUTH_SETTING` does
  not inherit the namespace's rights in the permission model, for the REST API as for this tool.
- In local mode (`LocalSecurityService`) every request to the service acts as the OS user, so an
  agent with a shell can still reach the REST API directly. The tool's description asks it not to;
  only a dedicated configuration agent without shell, terminal or browser tools guarantees it.
