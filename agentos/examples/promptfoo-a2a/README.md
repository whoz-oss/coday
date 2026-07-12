# Promptfoo × AgentOS A2A prototype

This folder shows how to test an AgentOS agent through the A2A prototype using
[promptfoo](https://www.promptfoo.dev/)'s built-in
[`a2a` provider](https://www.promptfoo.dev/docs/providers/a2a/).

## Setup

1. **Start AgentOS locally** (default port `8124`):

   ```bash
   nx run agentos:start
   ```

2. **Pick a namespace + agent.** The prototype exposes any `AgentConfig` with
   `enabled = true`. You can list them via:

   ```bash
   curl http://localhost:8124/api/agent-configs   # or via the UI
   ```

   Note both the namespace UUID and the agent name.

3. **Update `promptfooconfig.yaml`.** Replace `<NAMESPACE_ID>` and
   `<AGENT_NAME>` with the values from step 2.

4. **Install promptfoo** (no global install needed):

   ```bash
   npx promptfoo@latest --version
   ```

## Run

```bash
cd agentos/examples/promptfoo-a2a
npx promptfoo@latest eval -c promptfooconfig.yaml
npx promptfoo@latest view    # opens the results UI
```

## What promptfoo does

For each test in the config, promptfoo:

1. `POST http://localhost:8124/api/a2a/{ns}/{agent}/message:send`
   with a body like:
   ```json
   {
     "request": {
       "role": "ROLE_USER",
       "messageId": "<generated>",
       "parts": [{ "text": "<prompt>" }]
     }
   }
   ```
2. Reads the returned `Task` (status = `TASK_STATE_SUBMITTED` or
   `TASK_STATE_WORKING` at first).
3. Polls `GET .../tasks/{taskId}` every second until the task reaches
   `TASK_STATE_COMPLETED` (or another terminal state, or the timeout hits).
4. Extracts the final text from the task's artifacts (this is where the
   agent's response lives).
5. Runs the configured `assert` checks against that text.

Under the hood, each A2A task is one AgentOS `Case`. You can also observe the
same interaction in the Coday web UI (case chat) if it is running — the case
appears under the target namespace with the title derived from the prompt.

## Troubleshooting

- **`Tasks require additional input or authentication`** — the mapping treats
  AgentOS `IDLE` as `TASK_STATE_COMPLETED` (see docs/a2a.md §5.2). If you
  changed it back to `INPUT_REQUIRED`, promptfoo will report this error.

- **404 `Agent 'xxx' is not enabled for A2A exposure`** — set the agent's
  `enabled = true` via the AgentConfig admin UI or API.

- **Timeout** — increase `polling.timeoutMs` in the config. Advanced agents
  with multiple tool calls can easily exceed 5 minutes.

- **Streaming instead of polling** — swap `mode: send` for `mode: stream`.
  The prototype supports SSE at `/message:stream`.
