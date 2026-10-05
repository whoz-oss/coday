/**
 * Minimal AgentOS HTTP client shared by the promptfoo providers.
 *
 * Talks to AgentOS directly (default http://localhost:8124), not through the Angular dev proxy,
 * which may buffer the SSE stream. In local security mode the identity is the OS user running
 * AgentOS; in auth mode pass identity headers through the provider `headers` config.
 */

export const DEFAULT_AGENTOS_URL = 'http://localhost:8124'

export class AgentosClient {
  constructor({ url, headers } = {}) {
    this.url = (url ?? process.env.AGENTOS_URL ?? DEFAULT_AGENTOS_URL).replace(/\/$/, '')
    this.headers = headers ?? {}
  }

  async request(method, path, body) {
    const response = await fetch(`${this.url}${path}`, {
      method,
      headers: { ...this.headers, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
    if (!response.ok) {
      const text = await response.text().catch(() => '')
      const error = new Error(`AgentOS ${method} ${path} failed: ${response.status} ${text.slice(0, 300)}`)
      error.status = response.status
      throw error
    }
    const text = await response.text()
    return text ? JSON.parse(text) : undefined
  }

  get(path) {
    return this.request('GET', path)
  }

  post(path, body) {
    return this.request('POST', path, body ?? {})
  }

  /** Accepts a namespace id or name; falls back to AGENTOS_NAMESPACE. */
  async resolveNamespaceId(namespace) {
    const wanted = namespace ?? process.env.AGENTOS_NAMESPACE
    if (!wanted) throw new Error('Set the provider `namespace` config or AGENTOS_NAMESPACE (namespace id or name)')
    if (/^[0-9a-f-]{36}$/i.test(wanted)) return wanted
    const namespaces = await this.get('/api/namespaces')
    const match = namespaces.find((ns) => ns.name?.toLowerCase() === wanted.toLowerCase())
    if (!match) throw new Error(`AgentOS namespace '${wanted}' not found`)
    return match.id
  }

  /** Namespace-scoped and platform models, as AgentOS resolves them at runtime. */
  async listModels(namespaceId) {
    const [scoped, platform] = await Promise.all([
      this.get(`/api/ai-models/by-namespaceId/${namespaceId}`),
      this.get('/api/ai-models/platform'),
    ])
    return [...scoped, ...platform]
  }

  /**
   * Mirrors AiModelServiceImpl.findAiModel: alias first, then apiModelName (case-insensitive);
   * namespace-scoped beats platform, then higher priority wins.
   */
  async resolveModel(namespaceId, name) {
    const models = await this.listModels(namespaceId)
    const rank = (m) => [m.namespaceId ? 1 : 0, m.priority ?? 0]
    const best = (candidates) =>
      candidates.sort((a, b) => {
        const [sa, pa] = rank(a)
        const [sb, pb] = rank(b)
        return sb - sa || pb - pa
      })[0]
    const lower = name.toLowerCase()
    const model =
      best(models.filter((m) => m.alias?.toLowerCase() === lower)) ??
      best(models.filter((m) => m.apiModelName?.toLowerCase() === lower))
    if (!model) throw new Error(`No AgentOS model with alias or apiModelName '${name}' in namespace ${namespaceId}`)
    const provider = await this.get(`/api/ai-providers/${model.aiProviderId}`)
    return { model, provider }
  }

  /** Index of model pricing keyed by `${providerName}|${apiModelName}`, as stored on usage records. */
  async pricingIndex(namespaceId) {
    const models = await this.listModels(namespaceId)
    const providerIds = [...new Set(models.map((m) => m.aiProviderId))]
    const providers = await Promise.all(providerIds.map((id) => this.get(`/api/ai-providers/${id}`).catch(() => null)))
    const providerName = new Map(providers.filter(Boolean).map((p) => [p.id, p.name]))
    return new Map(models.map((m) => [`${providerName.get(m.aiProviderId)}|${m.apiModelName}`, m.pricing ?? null]))
  }
}

/** Cost from per-million-token pricing, mirroring CostCalculator (omitted rates count as zero). */
export function estimateCost(pricing, { inputTokens = 0, outputTokens = 0, cacheReadTokens = 0, cacheWriteTokens = 0 }) {
  if (!pricing) return null
  const rates = [pricing.inputMTokens, pricing.outputMTokens, pricing.cacheRead, pricing.cacheWrite]
  if (rates.every((rate) => rate === null || rate === undefined)) return null
  return (
    (inputTokens * (pricing.inputMTokens ?? 0) +
      outputTokens * (pricing.outputMTokens ?? 0) +
      cacheReadTokens * (pricing.cacheRead ?? 0) +
      cacheWriteTokens * (pricing.cacheWrite ?? 0)) /
    1_000_000
  )
}

/** Yields the `data:` payload of each SSE event of a fetch response, handling split chunks. */
export async function* readSse(response) {
  const decoder = new TextDecoder()
  let buffer = ''
  let data = []
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true })
    let newline
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).replace(/\r$/, '')
      buffer = buffer.slice(newline + 1)
      if (line === '') {
        if (data.length) yield data.join('\n')
        data = []
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''))
      }
    }
  }
  if (data.length) yield data.join('\n')
}
