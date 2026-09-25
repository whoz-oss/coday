/**
 * Pure HTTP transport over the AgentOS REST API.
 *
 * This module owns every AgentOS endpoint path. It performs no domain logic:
 * it serializes requests, applies the identity and binding-secret headers,
 * bounds every call with a timeout and throws an explicit error on non-2xx.
 */

import type { AgentConfigDTO, CaseDTO, CaseEventDTO, IntegrationConfigDTO } from './agentos-dtos.js'

/** Configuration of the AgentOS HTTP client. */
export interface AgentOsHttpClientConfig {
  baseUrl?: string
  userId?: string
  bindingSecret?: string
  fetchImpl?: typeof fetch
  timeoutMs?: number
}

/** Optional correlation when posting a message. */
export interface PostMessageOptions {
  /** Links the message to the question event it answers. */
  answerToEventId?: string
}

/** Transport surface consumed by the rest of the AgentOS adapter. */
export interface AgentOsHttpClient {
  readonly baseUrl: string
  readonly userId: string
  createCase(namespaceId: string, title: string): Promise<CaseDTO>
  postMessage(caseId: string, content: string, options?: PostMessageOptions): Promise<void>
  bindFactoryStepResult(caseId: string, binding: unknown): Promise<void>
  getCase(caseId: string): Promise<CaseDTO>
  listEvents(caseId: string): Promise<CaseEventDTO[]>
  killCase(caseId: string): Promise<void>
  interruptCase(caseId: string): Promise<void>
  listAgentConfigs(namespaceId: string): Promise<AgentConfigDTO[]>
  listIntegrationConfigs(namespaceId: string): Promise<IntegrationConfigDTO[]>
}

const DEFAULT_BASE_URL = 'http://localhost:8124'
const DEFAULT_USER_ID = 'benjamin.valdes'
const DEFAULT_TIMEOUT_MS = 15_000

/**
 * Builds an AgentOS HTTP client.
 *
 * Environment variables (`AGENTOS_URL`, `FACTORY_USER`,
 * `FACTORY_AGENTOS_BINDING_SECRET`) are read at construction so callers can
 * inject explicit values in tests.
 */
export function createAgentOsHttpClient(config: AgentOsHttpClientConfig = {}): AgentOsHttpClient {
  const baseUrl = config.baseUrl ?? process.env.AGENTOS_URL ?? DEFAULT_BASE_URL
  const userId = config.userId ?? process.env.FACTORY_USER ?? DEFAULT_USER_ID
  const bindingSecret = config.bindingSecret ?? process.env.FACTORY_AGENTOS_BINDING_SECRET
  const fetchImpl = config.fetchImpl ?? fetch
  const timeoutMs = config.timeoutMs ?? DEFAULT_TIMEOUT_MS

  async function request(method: string, path: string, body?: unknown): Promise<Response> {
    const url = `${baseUrl}${path}`
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-External-User-Id': userId,
    }
    const init: RequestInit = { method, headers, signal: AbortSignal.timeout(timeoutMs) }
    if (body !== undefined) {
      init.body = JSON.stringify(body)
    }

    const res = await fetchImpl(url, init)

    if (!res.ok) {
      let responseBody = ''
      try {
        responseBody = await res.text()
      } catch {
        // ignore: the status is the authoritative signal
      }
      throw new Error(`AgentOS ${method} ${url} → HTTP ${res.status}\n${responseBody}`)
    }

    return res
  }

  return {
    baseUrl,
    userId,

    async createCase(namespaceId: string, title: string): Promise<CaseDTO> {
      const res = await request('POST', '/api/cases', { namespaceId, title })
      return (await res.json()) as CaseDTO
    },

    async postMessage(caseId: string, content: string, options?: PostMessageOptions): Promise<void> {
      const payload: Record<string, unknown> = { content }
      if (options?.answerToEventId) {
        payload.answerToEventId = options.answerToEventId
      }
      await request('POST', `/api/cases/${encodeURIComponent(caseId)}/messages`, payload)
    },

    async bindFactoryStepResult(caseId: string, binding: unknown): Promise<void> {
      if (!bindingSecret) throw new Error('FACTORY_AGENTOS_BINDING_SECRET is required')
      const response = await fetchImpl(
        `${baseUrl}/internal/factory/cases/${encodeURIComponent(caseId)}/step-result-binding`,
        {
          method: 'PUT',
          headers: {
            'content-type': 'application/json',
            'x-factory-agentos-secret': bindingSecret,
          },
          body: JSON.stringify(binding),
          signal: AbortSignal.timeout(timeoutMs),
        }
      )
      if (!response.ok) {
        throw new Error(`AgentOS Factory binding rejected with HTTP ${response.status}`)
      }
    },

    async getCase(caseId: string): Promise<CaseDTO> {
      const res = await request('GET', `/api/cases/${encodeURIComponent(caseId)}`)
      return (await res.json()) as CaseDTO
    },

    async listEvents(caseId: string): Promise<CaseEventDTO[]> {
      const res = await request('GET', `/api/case-events/by-parentId/${encodeURIComponent(caseId)}`)
      return (await res.json()) as CaseEventDTO[]
    },

    async killCase(caseId: string): Promise<void> {
      await request('POST', `/api/cases/${encodeURIComponent(caseId)}/kill`)
    },

    async interruptCase(caseId: string): Promise<void> {
      await request('POST', `/api/cases/${encodeURIComponent(caseId)}/interrupt`)
    },

    async listAgentConfigs(namespaceId: string): Promise<AgentConfigDTO[]> {
      const res = await request('GET', `/api/agent-configs/by-parentId/${encodeURIComponent(namespaceId)}`)
      return (await res.json()) as AgentConfigDTO[]
    },

    async listIntegrationConfigs(namespaceId: string): Promise<IntegrationConfigDTO[]> {
      const res = await request('GET', `/api/integration-configs?namespaceId=${encodeURIComponent(namespaceId)}`)
      return (await res.json()) as IntegrationConfigDTO[]
    },
  }
}
