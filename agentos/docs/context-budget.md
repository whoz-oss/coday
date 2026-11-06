# Context budget

Every agent invocation measures how much of its model's context window its prompts use. This
is Phase 1 of [ADR 002](adr/002-context-compaction.md): it **only measures**. Nothing is
removed from a prompt yet; compaction (Phase 2) will use the same budget as its trigger.

## Configure a model

Set `contextWindow` on the model: the total window in tokens (input + output), as documented
by the provider for that exact model. It can be set through the API (`AiModelDto`), the admin
UI, or a filesystem model file:

```yaml
# <configPath>/ai-models/sonnet.yaml
alias: BIG
apiModelName: anthropic/claude-sonnet-5-5
provider: requesty
contextWindow: 1000000
maxCompletionTokens: 16000
```

A model without `contextWindow` has **no budget**: it is never reported as reaching its limit,
and AgentOS never guesses a value. For a self-hosted model (Ollama, vLLM), use the window the
server actually serves (Ollama's `num_ctx`), not the model's architectural maximum.

The bundled `agentos/aimodel/` files set it where it is known: `claude-haiku.yaml` (200 000),
`gpt-5-mini.yaml` (400 000) and the `gpt-4o` example (128 000). The other examples leave it
commented out.

## How the budget is computed

| Value | Formula |
|---|---|
| usable | `contextWindow - maxCompletionTokens` (the window minus the room kept for the answer) |
| trigger | `context-compaction-threshold x usable` (default 0.8) |
| target | `context-compaction-target x usable` (default 0.5, used by Phase 2) |

A window not larger than `maxCompletionTokens` leaves nothing usable and disables the budget.

Before each request, the **whole** prompt is estimated: message texts, tool-call arguments,
tool responses and tool definitions (name, description, input schema). The first estimate uses
3 characters per token, deliberately below the usual 4 so that it errs on the large side.
After each response that reports the prompt size the provider counted (Anthropic and
OpenAI-compatible native usage: input + cache read + cache write; otherwise the generic prompt
count, e.g. Gemini), the ratio is replaced by the measured one for the rest of the invocation.
The last measurement wins, bounded to 1..8 characters per token, so images or truncated usage
cannot distort it. Without usage tracking, OpenAI-compatible streams report no usage and the
estimate stays at 3 characters per token.

The budget is per invocation: a new agent run starts again from 3 characters per token.

## What happens today

- Every request is logged at DEBUG with its estimate, the trigger, the usable window and the
  current ratio (`[ContextBudget] case=... agent=... model=... prompt ≈ N tokens, trigger T of usable U`).
- The first request of an invocation that reaches the trigger is logged **once** at WARN.
- Nothing else changes: the request is sent as is.

The WARN lines show how often compaction would apply, and on which agents and models. When a
prompt really overflows the window, the provider error is reported explicitly in the case (see
ADR 002, Phase 0).

## Settings

| Property | Env var | Default |
|---|---|---|
| `agentos.limits.context-compaction-threshold` | `AGENTOS_LIMITS_CONTEXT_COMPACTION_THRESHOLD` | `0.8` |
| `agentos.limits.context-compaction-target` | `AGENTOS_LIMITS_CONTEXT_COMPACTION_TARGET` | `0.5` |

They must satisfy `0 < target < threshold <= 1`; the service refuses to start otherwise. The
gap between them is what keeps the start of the prompt stable between two compactions, so that
prompt caching keeps working.

## Where it lives

- `compaction/ContextBudget.kt`: budget, estimate and correction, one instance per invocation.
- `AgentServiceImpl.contextBudgetFor`: builds it from the resolved model for a live agent run
  (`AgentExecutionContext.observeContext`, set by `CaseServiceImpl`).
- `chat/UsageTrackingChatModel.kt`: the agent's chat model. It owns the tool loop and observes
  every request, whatever `agentos.usage.enabled` says; its cost part (accounting, cost gate)
  only runs when usage tracking is enabled.
- Clients outside agent execution (case naming, prompt translation) have no budget.
