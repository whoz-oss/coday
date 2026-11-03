/**
 * Minimal AgentOS REST client for the setup apply step. Called from the Express server (never the
 * browser), straight on the AgentOS loopback port, as the first-run user through
 * `X-External-User-Id`. Only the handful of endpoints the wizard needs.
 */

export interface AgentosProvider {
  id?: string
  name: string
  description?: string
  apiType: string
  baseUrl?: string
  apiKey?: string
  namespaceId?: string | null
  userId?: string | null
}

/** USD per million tokens; AgentOS answers an unset rate as null or omits it. */
export interface AgentosModelPricing {
  inputMTokens?: number | null
  outputMTokens?: number | null
  cacheRead?: number | null
  cacheWrite?: number | null
}

export interface AgentosUser {
  id?: string
  externalId?: string
  isAdmin?: boolean
}

export interface AgentosModel {
  id?: string
  aiProviderId: string
  apiModelName: string
  alias?: string
  priority: number
  description?: string
  contextWindow?: number
  maxCompletionTokens?: number
  pricing?: AgentosModelPricing | null
}

export interface AgentosNamespace {
  id?: string
  name: string
  externalId?: string
  defaultAgentName?: string
  sharedExchangePath?: string
  [key: string]: unknown
}

export interface AgentosAgent {
  id?: string
  name: string
  enabled?: boolean
  [key: string]: unknown
}

export class AgentosHttpError extends Error {
  constructor(
    readonly status: number,
    readonly method: string,
    readonly path: string,
    readonly body: string
  ) {
    super(`AgentOS ${method} ${path} -> HTTP ${status}${body ? `: ${body.slice(0, 300)}` : ''}`)
  }
}

export class AgentosClient {
  constructor(
    /** A function: the AgentOS port is only known once the provision step ran. */
    private readonly baseUrl: string | (() => string),
    private readonly userExternalId: string,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  private base(): string {
    return typeof this.baseUrl === 'function' ? this.baseUrl() : this.baseUrl
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.fetchFn(`${this.base()}${path}`, {
      method,
      headers: {
        Accept: 'application/json',
        'X-External-User-Id': this.userExternalId,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    })
    const text = await res.text()
    if (!res.ok) throw new AgentosHttpError(res.status, method, path, text)
    return (text ? JSON.parse(text) : undefined) as T
  }

  /** Platform-scope providers. Without a query parameter AgentOS answers the caller's own providers instead. */
  listPlatformProviders(): Promise<AgentosProvider[]> {
    return this.request('GET', '/api/ai-providers?namespaceId=none')
  }

  createProvider(provider: AgentosProvider): Promise<AgentosProvider> {
    return this.request('POST', '/api/ai-providers', { ...provider, namespaceId: null, userId: null })
  }

  updateProvider(id: string, provider: AgentosProvider): Promise<AgentosProvider> {
    return this.request('PUT', `/api/ai-providers/${id}`, { ...provider, namespaceId: null, userId: null })
  }

  listModelsOfProvider(providerId: string): Promise<AgentosModel[]> {
    return this.request('GET', `/api/ai-models/by-parentId/${providerId}`)
  }

  createModel(model: AgentosModel): Promise<AgentosModel> {
    return this.request('POST', '/api/ai-models', model)
  }

  updateModel(id: string, model: AgentosModel): Promise<AgentosModel> {
    return this.request('PUT', `/api/ai-models/${id}`, model)
  }

  /** The calling user. AgentOS creates it (and its personal namespace) on the first request it sees. */
  getMe(): Promise<AgentosUser> {
    return this.request('GET', '/api/users/me')
  }

  findNamespacesByExternalIds(externalIds: string[]): Promise<AgentosNamespace[]> {
    return this.request('POST', '/api/namespaces/by-external-ids', externalIds)
  }

  updateNamespace(id: string, namespace: AgentosNamespace): Promise<AgentosNamespace> {
    return this.request('PUT', `/api/namespaces/${id}`, namespace)
  }

  /** Agents of the namespace visible to the user (deployed platform agents included). */
  searchAgents(namespaceId: string): Promise<AgentosAgent[]> {
    return this.request('POST', '/api/agent-configs/search', {
      namespaceId,
      userExternalId: this.userExternalId,
    })
  }
}
