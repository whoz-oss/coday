import { AgentosClient, estimateCost, readSse } from './agentos-client.mjs'

// ChatModelFactory defaults.
const AGENTOS_DEFAULT_TEMPERATURE = 1.0
const AGENTOS_OPENAI_BASE_URL = 'https://api.openai.com'

/**
 * promptfoo provider calling a model directly, addressed by its AgentOS alias (BIG, SMALL, …).
 *
 * The alias is resolved as AgentOS does (alias, then apiModelName; namespace beats platform; then
 * priority; see AgentosClient.resolveModel for the differences) and the request goes straight to that
 * model's OpenAI-compatible provider (e.g. Requesty), shaped as ChatModelFactory shapes it. AgentOS
 * masks provider keys, so the key comes from the environment (`apiKeyEnvar`, default REQUESTY_API_KEY).
 *
 * Cost (config `costSource`):
 * - `provider` (default): the gateway's own `usage.cost` (scenario B), falling back to pricing.
 * - `pricing`: tokens × AgentOS AiModel.pricing (scenario A).
 * Both figures are always available in the result metadata.
 *
 * Config: alias, namespace, url, headers, apiKeyEnvar, baseUrl (overrides the provider's), costSource,
 * temperature, maxTokens, reasoningEffort (overrides the model's: NONE, LOW, MEDIUM, HIGH, XHIGH, MAX).
 * Also usable as a promptfoo grader provider (llm-rubric, factuality, …).
 */
export default class AgentosModelProvider {
  constructor(options = {}) {
    this.config = options.config ?? {}
    this.client = new AgentosClient({ url: this.config.url, headers: this.config.headers })
    this.resolved = null
  }

  id() {
    return `agentos-model:${this.config.alias}`
  }

  async resolve() {
    if (!this.config.alias) throw new Error('agentos-model provider requires the `alias` config')
    this.resolved ??= this.client
      .resolveNamespaceId(this.config.namespace)
      .then((namespaceId) => this.client.resolveModel(namespaceId, this.config.alias))
      .catch((error) => {
        // Not cached: a transient failure (AgentOS not up yet) must not fail every later test.
        this.resolved = null
        throw error
      })
    return this.resolved
  }

  async callApi(prompt) {
    let model, provider
    try {
      ;({ model, provider } = await this.resolve())
    } catch (error) {
      return { error: String(error.message ?? error) }
    }
    if (provider.apiType !== 'OpenAI' && provider.apiType !== 'vLLM') {
      return {
        error: `Alias '${this.config.alias}' uses a ${provider.apiType} provider; only OpenAI-compatible providers are supported`,
      }
    }
    const keyVar = this.config.apiKeyEnvar ?? 'REQUESTY_API_KEY'
    const apiKey = process.env[keyVar]
    if (!apiKey)
      return { error: `Set ${keyVar} to call provider '${provider.name}' (AgentOS does not expose its keys)` }

    // Request shaped as ChatModelFactory shapes it, so a model behaves as it does inside an agent.
    const vllm = provider.apiType === 'vLLM'
    const baseUrl = this.config.baseUrl ?? provider.baseUrl ?? (vllm ? null : AGENTOS_OPENAI_BASE_URL)
    if (!baseUrl) return { error: `vLLM provider '${provider.name}' has no baseUrl` }
    // Spring AI appends the full path, so a base URL ending in /v1 fails in AgentOS too.
    const endpoint = `${baseUrl.replace(/\/$/, '')}/v1/chat/completions`
    const temperature = this.config.temperature ?? model.temperature ?? AGENTOS_DEFAULT_TEMPERATURE
    const maxTokens = this.config.maxTokens ?? model.maxCompletionTokens
    // Like a case-level effort, the config overrides the model's.
    const effort = reasoningEffortSent(this.config.reasoningEffort ?? model.reasoningEffort, vllm)
    const body = {
      model: model.apiModelName,
      messages: toMessages(prompt),
      stream: true,
      stream_options: { include_usage: true },
      temperature,
      ...(maxTokens ? { [vllm ? 'max_tokens' : 'max_completion_tokens']: maxTokens } : {}),
      ...(effort ? { reasoning_effort: effort } : {}),
      ...(vllm ? { chat_template_kwargs: { enable_thinking: effort !== null } } : {}),
    }
    // AgentOS only sends the provider's custom headers to vLLM providers.
    const extraHeaders = vllm ? (provider.headers ?? {}) : {}

    const startedAt = performance.now()
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}`, ...extraHeaders },
      body: JSON.stringify(body),
    })
    if (!response.ok) {
      return { error: `${provider.name} ${response.status}: ${(await response.text()).slice(0, 300)}` }
    }

    let output = ''
    let usage = null
    let firstChunkAt = null
    for await (const data of readSse(response)) {
      if (data === '[DONE]') break
      const chunk = JSON.parse(data)
      const delta = chunk.choices?.[0]?.delta?.content
      if (delta) {
        firstChunkAt ??= performance.now()
        output += delta
      }
      if (chunk.usage) usage = chunk.usage
    }
    const latencyMs = Math.round(performance.now() - startedAt)

    const cached = usage?.prompt_tokens_details?.cached_tokens ?? 0
    const tokens = {
      inputTokens: Math.max((usage?.prompt_tokens ?? 0) - cached, 0),
      outputTokens: usage?.completion_tokens ?? 0,
      cacheReadTokens: cached,
      cacheWriteTokens: 0,
    }
    const providerCost = typeof usage?.cost === 'number' ? usage.cost : null
    const pricingCost = estimateCost(model.pricing, tokens)
    const cost = this.config.costSource === 'pricing' ? pricingCost : (providerCost ?? pricingCost)

    return {
      output,
      cost: cost ?? undefined,
      tokenUsage: {
        prompt: usage?.prompt_tokens,
        completion: usage?.completion_tokens,
        total: usage?.total_tokens,
        cached,
      },
      latencyMs,
      cached: false,
      metadata: {
        alias: this.config.alias,
        model: model.apiModelName,
        provider: provider.name,
        reasoningEffort: effort,
        ttftMs: firstChunkAt ? Math.round(firstChunkAt - startedAt) : null,
        usage: { ...tokens, providerCost, pricingCost },
      },
    }
  }
}

/**
 * The `reasoning_effort` value AgentOS sends (ReasoningEffortMapping.kt), or null when none is sent:
 * OpenAI gets the level verbatim; vLLM stops at high, and NONE turns its thinking off.
 */
function reasoningEffortSent(effort, vllm) {
  if (!effort) return null
  const level = effort.toLowerCase()
  if (!vllm) return level
  if (level === 'none') return null
  return level === 'xhigh' || level === 'max' ? 'high' : level
}

/** promptfoo passes chat prompts as a JSON array of messages; anything else is one user message. */
function toMessages(prompt) {
  try {
    const parsed = JSON.parse(prompt)
    if (Array.isArray(parsed) && parsed.every((m) => m?.role)) return parsed
  } catch {
    // plain text prompt
  }
  return [{ role: 'user', content: prompt }]
}
