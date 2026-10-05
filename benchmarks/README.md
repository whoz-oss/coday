# AgentOS benchmarks (promptfoo)

Benchmarks models and agents of a running AgentOS for **quality, cost and timing**, with
[promptfoo](https://www.promptfoo.dev). Results open in a local web UI.

This folder is a standalone pnpm root (its own lockfile): promptfoo's large dependency tree never
touches the main workspace lockfile.

## Setup

```bash
cd benchmarks
nvm use                        # repo .nvmrc: Node 22.22.0 (promptfoo minimum); Node 24 works too
cp .env.example .env           # then fill REQUESTY_API_KEY, AGENTOS_NAMESPACE
pnpm install
```

AgentOS must be running (default `http://localhost:8124`) with usage tracking on (the default;
`AGENTOS_USAGE_ENABLED`) for recorded agent costs.

## Run

```bash
pnpm eval                      # runs promptfooconfig.yaml (cache disabled: latency/cost are real)
pnpm eval -c my-suite.yaml     # any other suite
pnpm ui                        # web UI on http://localhost:15500
```

Each result cell shows output, pass/fail, cost, tokens and latency. Its details hold the metadata
below, including a link to the full conversation in the AgentOS UI for agent runs.

## Providers

| Provider | What it runs | Config |
|---|---|---|
| `file://providers/agentos-agent.mjs` | One AgentOS agent turn: creates a case, posts `@agent <prompt>`, waits until the case is IDLE | `agent`, `namespace`, `timeoutMs` (600000), `deleteCase` (false), `titlePrefix` (`[bench]`), `costSource`, `reasoningEffort`, `headers`, `url` |
| `file://providers/agentos-model.mjs` | The model behind an AgentOS alias (`BIG`, `SMALL`, …), resolved as AgentOS does, called directly | `alias`, `namespace`, `apiKeyEnvar` (`REQUESTY_API_KEY`), `baseUrl`, `costSource`, `temperature`, `maxTokens`, `reasoningEffort`, `headers`, `url` |

`agentos-model.mjs` also works as a grader (`defaultTest.options.provider`) for `llm-rubric`,
`factuality`, … Only providers of type `OpenAI` (including gateways such as Requesty) and `vLLM` are
supported. The request has the shape AgentOS gives it. Resolution differs from an agent run in three
ways: an unknown alias is an error instead of falling back to `default`, user-global models are not
visible, and the user's provider overlays are not applied.

## Cost: two sources

| | Agent runs (`agentos-agent`) | Model only (`agentos-model`) |
|---|---|---|
| **B — gateway cost** (default) | `costSource: recorded`: what AgentOS stored. It is the gateway's `usage.cost` (e.g. Requesty) when AgentOS captured it, else the pricing estimate | `costSource: provider`: the gateway's `usage.cost`, falling back to pricing |
| **A — AgentOS pricing** | `costSource: pricing`: tokens of each usage record × the AiModel pricing | `costSource: pricing`: tokens × the alias's AiModel pricing |

Both figures are always in `metadata.usage` (`recordedCost` / `pricingCost`, or `providerCost` /
`pricingCost`), whatever `costSource` is. An unknown cost (unpriced model) is reported as no cost.
For agent runs, the known lower bound is in `metadata.usage.recordedCostLowerBound`. When AgentOS
runs without usage tracking, agent runs report no tokens and no cost (`metadata.usage.source:
unavailable`).

AgentOS stores the gateway cost only when built from a version that captures it
(`feat(agentos-service): store provider-reported cost for LLM usage`). Before that, recorded costs
are the pricing estimate. AgentOS's own utility calls (e.g. the case summary after each turn) are not
recorded, so they are not in agent run costs.

## Metadata (agent runs)

`caseId`, `caseUrl`, `selectedAgent` (agent AgentOS selected for the turn), `agents` (name, provider,
model actually used), `ttftMs` (first streamed text), `toolCalls` (name, args, success, durationMs),
`toolNames`, `steps` (planning iterations of advanced agents), `subCases`, `maxIterationsReached` (tool
round limit when the agent stopped there and summarized its progress), `warnings`, `usage`. Use them in `javascript` assertions through
`context.providerResponse.metadata`.

## Things to know

- **Agents run their real tools.** An agent with write integrations (files, git, Jira, …) acts for
  real. Benchmark agents with read-only integrations, or in a dedicated namespace.
- **Turns waiting for a human are errors**: a pending tool confirmation, or a question without an
  answer. A case ending in `ERROR` is an error too.
- **The agent must exist and be accessible** to the calling user in the namespace (or at platform
  level). Otherwise AgentOS runs the default agent instead. The provider reports that as an error, so
  a different agent's answer is never graded. AgentOS matches a mention by name prefix and only
  routes names made of letters, digits, `_` and `-`; other names are rejected before any case is created.
- **Model aliases must exist** in the namespace (or at platform level). Otherwise an agent falls back
  to the `default` alias, and fails if there is none.
- **`reasoningEffort`** overrides the models' configured effort: on the case for agent runs, on the
  request for `agentos-model`, sent as AgentOS sends it (vLLM stops at `high`; `NONE` turns its
  thinking off).
- Cases are titled `[bench] …`, which also skips AgentOS's automatic title LLM call. They appear in
  the namespace case list and usage report; `deleteCase: true` soft-deletes them after the run
  (usage records are kept).
- A run cost threshold pauses an agent; the run then ends with a timeout error mentioning it.
- AgentOS is called directly, not through the Angular dev proxy, which may buffer the event stream.
  In auth mode, pass identity headers (e.g. `X-External-User-Id`) through `headers`.
