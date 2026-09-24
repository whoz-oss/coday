import type { CaseTerminator } from '../ports/case-terminator.js'

export interface AgentOsCaseTerminatorOptions {
  baseUrl: string
  userId: string
  timeoutMs?: number
  fetchImpl?: typeof fetch
}

/** Minimal AgentOS adapter: one bounded request, deliberately no retry. */
export function createAgentOsHttpCaseTerminator(options: AgentOsCaseTerminatorOptions): CaseTerminator {
  const fetchImpl = options.fetchImpl ?? fetch
  const timeoutMs = options.timeoutMs ?? 5_000
  return {
    async terminate(caseId) {
      const response = await fetchImpl(`${options.baseUrl}/api/cases/${encodeURIComponent(caseId)}/kill`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-External-User-Id': options.userId },
        signal: AbortSignal.timeout(timeoutMs),
      })
      if (!response.ok) throw new Error(`AgentOS kill ${caseId} returned HTTP ${response.status}`)
    },
  }
}
