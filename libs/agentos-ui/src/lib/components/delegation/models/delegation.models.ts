import { CaseEvent, SubCaseFinishedEvent, SubCaseStartedEvent, ToolResponseEvent } from '@whoz-oss/agentos-api-client'

export type DelegationStatus = 'WORKING' | 'SUCCESS' | 'WAITING_USER' | 'ERROR' | 'TIMEOUT' | 'KILLED'

export interface DelegationResult {
  delegationId: string
  toolRequestId: string
  subCaseId?: string
  agentName?: string
  success?: boolean
  result?: string
  pendingQuestion?: string
  options?: string[]
  error?: string
  errorType?: string
}

export interface DelegationPresentation {
  delegationId: string
  toolRequestId: string
  subCaseId: string
  agentName: string
  task: string
  resumed: boolean
  status: DelegationStatus
  result?: DelegationResult
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

/**
 * OpenAPI currently describes output as an empty object although the SSE payload can be a raw
 * string, a MessageContent-like { content } object, or nested content parts. Extract the first
 * textual representation without assuming one generated client shape.
 */
export function extractToolOutputText(output: unknown): string | null {
  if (typeof output === 'string') return output
  if (Array.isArray(output)) {
    for (const item of output) {
      const text = extractToolOutputText(item)
      if (text !== null) return text
    }
    return null
  }
  const value = asRecord(output)
  if (!value) return null
  for (const key of ['content', 'text', 'output']) {
    const text = extractToolOutputText(value[key])
    if (text !== null) return text
  }
  return null
}

/** Parses only the documented delegation response shape; invalid tool output stays on the raw fallback card. */
export function parseDelegationResults(response: ToolResponseEvent | undefined): DelegationResult[] | null {
  const content = extractToolOutputText(response?.output)
  if (content === null) return null
  try {
    const parsed: unknown = JSON.parse(content)
    const entries = Array.isArray(parsed) ? parsed : [parsed]
    const results = entries.map((entry): DelegationResult | null => {
      const value = asRecord(entry)
      if (!value || typeof value['delegationId'] !== 'string' || typeof value['toolRequestId'] !== 'string') return null

      const result: DelegationResult = {
        delegationId: value['delegationId'],
        toolRequestId: value['toolRequestId'],
      }
      if (typeof value['subCaseId'] === 'string') result.subCaseId = value['subCaseId']
      if (typeof value['agentName'] === 'string') result.agentName = value['agentName']
      if (typeof value['success'] === 'boolean') result.success = value['success']
      if (typeof value['result'] === 'string') result.result = value['result']
      if (typeof value['pendingQuestion'] === 'string') result.pendingQuestion = value['pendingQuestion']
      if (Array.isArray(value['options'])) {
        result.options = value['options'].filter((option): option is string => typeof option === 'string')
      }
      if (typeof value['error'] === 'string') result.error = value['error']
      if (typeof value['errorType'] === 'string') result.errorType = value['errorType']
      return result
    })
    return results.every((result): result is DelegationResult => result !== null) ? results : null
  } catch {
    return null
  }
}

export function buildDelegations(events: CaseEvent[]): DelegationPresentation[] {
  const started = new Map<string, SubCaseStartedEvent>()
  const finished = new Map<string, SubCaseFinishedEvent>()
  const results = new Map<string, DelegationResult>()
  for (const event of events) {
    if (event.type === 'SubCaseStartedEvent') started.set(event.delegationId, event as SubCaseStartedEvent)
    if (event.type === 'SubCaseFinishedEvent') finished.set(event.delegationId, event as SubCaseFinishedEvent)
    if (event.type === 'ToolResponseEvent') {
      for (const result of parseDelegationResults(event as ToolResponseEvent) ?? [])
        results.set(result.delegationId, result)
    }
  }
  const ids = new Set([...started.keys(), ...results.keys()])
  return [...ids].map((delegationId) => {
    const start = started.get(delegationId)
    const result = results.get(delegationId)!
    const finish = finished.get(delegationId)
    const status =
      finish?.outcome ??
      (result?.pendingQuestion ? 'WAITING_USER' : result ? (result.success === false ? 'ERROR' : 'SUCCESS') : 'WORKING')
    return {
      delegationId,
      toolRequestId: start?.toolRequestId ?? result.toolRequestId,
      subCaseId: start?.subCaseId ?? result.subCaseId ?? '',
      agentName: start?.agentName ?? result.agentName ?? 'Delegated agent',
      task: start?.task ?? 'Delegated task',
      resumed: start?.resumed ?? false,
      status,
      result,
    }
  })
}

export function isCorrelatedDelegateTool(
  toolName: string,
  requestId: string,
  response: ToolResponseEvent | undefined,
  delegations: DelegationPresentation[]
): boolean {
  if (toolName !== 'DELEGATE__delegate') return false
  if (!delegations.some((delegation) => delegation.toolRequestId === requestId)) return false
  // While running, Started is sufficient. Once a response exists it must contain valid structured
  // JSON correlated to this exact request; otherwise preserve the raw tool card as a diagnostic fallback.
  if (!response) return true
  return parseDelegationResults(response)?.some((result) => result.toolRequestId === requestId) ?? false
}
