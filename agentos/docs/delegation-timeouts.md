# Delegation timeouts

`DELEGATE__delegate` waits for each child case to become idle or reach a terminal
status. Its waiting budget is selected from the **delegating agent**, in seconds:

1. The agent's `delegationTimeoutSeconds`, when explicitly set.
2. The server's `agentos.defaults.delegation-timeout-seconds`.
3. The application default of **300 seconds** (five minutes).

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

## Nested delegations and expiration

For `ProductEngineer → BmadOrchestrator → BmadBuilder`, ProductEngineer's setting
bounds the wait for the entire Orchestrator turn, including its wait for Builder.
Giving Orchestrator a longer budget for Builder does not extend ProductEngineer's
budget. Configure the outer agent with enough time for the whole delegated task.

The timer measures elapsed time, not inactivity. Each parallel delegation has its
own timer, starting after its child case has been created or resumed. Loading the
completed case's event history has a separate timeout.

On expiration, AgentOS requests termination of the child and its active
descendants and returns `TIMEOUT`. A killed case cannot be resumed through
`subCaseId`, which requires an idle case. When an agent becomes idle with a pending
question before expiration, the question is returned to the parent as usual.
