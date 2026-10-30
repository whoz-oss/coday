# Delegation timeouts

`DELEGATE__delegate` waits for each child case to become idle or reach a terminal
status. It gives up only when the child shows no activity for a whole budget, selected
from the **delegating agent**, in seconds:

1. The agent's `delegationTimeoutSeconds`, when explicitly set.
2. The server's `agentos.defaults.delegation-timeout-seconds`.
3. The application default of **600 seconds** (ten minutes).

## Server configuration

Set the default for all agents without an override in Spring configuration:

```yaml
agentos:
  defaults:
    delegation-timeout-seconds: 1800
```

Or use the environment variable wired in the shipped `application.yml`:

```shell
AGENTOS_DEFAULTS_DELEGATION_TIMEOUT_SECONDS=1800
```

Restart AgentOS after changing its server configuration. All instances serving
the same deployment should receive the same setting. The default is not copied
into agent records, so existing agents without an override inherit it immediately
on their next tool construction. A delegation already running keeps its original
budget.

## Per-agent override

In the namespace or platform agent form, the **Delegation timeout (seconds)** field
is below **Sub-agents**. Leave it empty, or click **Use server default**, to inherit.
The form fetches the actual default from `GET /api/agent-configs/defaults`, which
requires authentication. If unavailable, it displays no guessed numeric default.

The same field is accepted by the agent API and filesystem YAML definitions:

```yaml
name: ProductEngineer
subAgents:
  - BmadOrchestrator
delegationTimeoutSeconds: 3600
```

Values must be positive integers, up to 2147483647 seconds. Zero does not mean
unlimited. Invalid server configuration fails at startup; invalid API values are
rejected. Invalid YAML agents are skipped with the existing configuration-load
error reporting.

On `PUT /api/agent-configs/{id}`, a null **or omitted** `delegationTimeoutSeconds`
clears the override. Clients editing an unrelated property must round-trip an
existing explicit value, as the AgentOS form does. Exports include only explicit
overrides, keeping inherited agents portable between environments.

## Activity, nested delegations and expiration

The timer measures inactivity, not elapsed time. Every event the child case emits
restarts it: the start of an LLM call, a tool call or its result, a streamed text
chunk, a message, a status change. The model's reasoning within a call emits nothing
(see below). Activity is sampled once a second. A child that keeps
working is never cut off by this timer; its run stays bounded by
`agentos.limits.agent-max-iterations` and `agentos.limits.case-max-iterations`.

For `ProductEngineer → BmadOrchestrator → BmadBuilder`, Orchestrator emits nothing
while it waits for Builder, so its delegation relays Builder's activity to
Orchestrator's case: ProductEngineer keeps waiting as long as Builder works. Activity
is only relayed when it happens: while Builder is silent, Orchestrator looks silent
too, so ProductEngineer's budget bounds the silence of the whole delegated subtree.
Giving Orchestrator a longer budget for Builder does not extend ProductEngineer's;
give the outer agent a budget at least as long as the longest silent step below it.

When usage tracking is enabled, time spent waiting for a human cost confirmation, on
the child or on one of its descendants, is not counted. Each parallel delegation has
its own timer, starting after its child case has been created or resumed. Loading the
completed case's event history has a separate timeout.

A single silent step longer than the budget still expires it: an LLM call that
streams nothing for that long (a long reasoning, a large tool call being written) or
a long tool run. Raise the delegating agent's budget if its children legitimately
stay silent that long.

On expiration, AgentOS stops the child and its active descendants as the Stop button
does: the call in progress is cancelled and the child ends idle. The delegation
returns `TIMEOUT` with the child's `subCaseId`; the delegating agent resumes the child
by delegating again with that `subCaseId`, and the child continues from its history.
If the child cannot be stopped, the result says so and does not offer to resume it.
When an agent becomes idle with a pending question before expiration, the question is
returned to the parent as usual.
