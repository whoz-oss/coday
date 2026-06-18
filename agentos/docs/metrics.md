# Metrics

AgentOS exposes metrics via [Micrometer](https://micrometer.io/). Any Micrometer-compatible
backend can be wired in; Datadog is supported out of the box.

## Enabling Datadog export

Export is **disabled by default**. Set the following environment variables to activate it:

| Variable | Required | Description |
|---|---|---|
| `DATADOG_METRICS_ENABLED` | yes | Set to `true` to enable export |
| `DATADOG_API_KEY` | yes | Datadog API key |
| `DATADOG_APP_KEY` | no | Datadog application key (optional) |
| `DATADOG_SITE` | no | Datadog site. Defaults to `datadoghq.com`; use `datadoghq.eu` for EU tenants |

Metrics are pushed every **30 seconds**.

All metrics are tagged with:
- `application: agentos`
- `environment: <active Spring profile>` (defaults to `local`)

## Custom metrics

All custom metrics are produced by `ToolMetricsService` and instrument tool execution
inside `AgentAdvanced`.

### `agentos.tool.calls.total` — Counter

Incremented once per tool execution, whether it succeeds or fails.

| Tag | Values | Description |
|---|---|---|
| `tool.name` | e.g. `FILES__read` | Fully-qualified tool name |
| `integration.type` | e.g. `FILES`, `JIRA` | Prefix before `__`; `unknown` when absent |
| `agent.name` | e.g. `copilot` | Name of the agent that called the tool |
| `namespace.id` | UUID | Namespace the case belongs to |
| `status` | `success` / `failure` | Outcome of the tool execution |

### `agentos.tool.calls.duration` — Timer

Wall-clock duration of each tool execution, measured with `Timer.Sample`
(nanosecond precision). Always emitted alongside `agentos.tool.calls.total`.

Same tags as `agentos.tool.calls.total`.

Datadog exposes this as a distribution: `avg`, `p50`, `p95`, `p99`, `max`.

### `agentos.tool.parameter_generation.failures` — Counter

Incremented when `AgentAdvanced` exhausts all retry attempts and cannot produce
valid JSON parameters for a tool call (`args = null`).

| Tag | Values | Description |
|---|---|---|
| `tool.name` | e.g. `FILES__read` | Fully-qualified tool name |
| `agent.name` | e.g. `copilot` | Name of the agent |
| `namespace.id` | UUID | Namespace the case belongs to |

### `agentos.tool.confirmation.total` — Counter

Incremented once per confirmation flow resolution (tools with
`ConfirmationMode.EVERY_TIME` or `ConfirmationMode.INFER`).

| Tag | Values | Description |
|---|---|---|
| `tool.name` | e.g. `FILES__remove` | Fully-qualified tool name |
| `agent.name` | e.g. `copilot` | Name of the agent |
| `namespace.id` | UUID | Namespace the case belongs to |
| `outcome` | `applied` / `rejected` / `aborted` | `applied`: user confirmed and tool succeeded; `rejected`: user declined; `aborted`: tool threw after confirmation |

## Standard Spring Boot metrics

Spring Boot auto-configures the following on top of the custom metrics above.
They are exported to Datadog under the same `application`/`environment` tags.

| Metric prefix | Description |
|---|---|
| `http.server.requests` | HTTP request durations and status codes (MVC) |
| `jvm.*` | JVM memory, GC, threads, classes |
| `process.*` | CPU usage, uptime |
| `system.*` | System CPU |
| `logback.*` | Log event counts by level |
| `spring.ai.*` | Spring AI model call observations (when enabled) |
