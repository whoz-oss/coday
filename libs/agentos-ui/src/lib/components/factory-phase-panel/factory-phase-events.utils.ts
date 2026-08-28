import {
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  AnswerEvent,
  CaseEvent,
  CaseStatusEvent,
  ErrorEvent,
  IntentionGeneratedEvent,
  MessageEvent as CaseMessageEvent,
  QuestionEvent,
  Text,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { FactoryRunPhase } from '../../services/factory-api.service'

// ---------------------------------------------------------------------------
// caseId extraction
// ---------------------------------------------------------------------------

/**
 * Extract the phase-level caseId from facts.caseId.
 * Returns null if not present — never falls back to a run-level caseId.
 */
export function extractPhaseCaseId(phase: FactoryRunPhase): string | null {
  const id = phase.facts?.['caseId']
  return typeof id === 'string' && id.length > 0 ? id : null
}

// ---------------------------------------------------------------------------
// Event row projection
// ---------------------------------------------------------------------------

export type PhaseEventRowKind =
  | 'message'
  | 'agent-selected'
  | 'agent-running'
  | 'agent-finished'
  | 'case-status'
  | 'tool'
  | 'warn'
  | 'error'
  | 'intention'
  | 'question'
  | 'answer'
  | 'unknown'

export interface ToolCallSummary {
  requestId: string
  toolName: string
  args: string | null
  success: boolean | null
  durationMs: number | null
  outputPreview: string | null
}

export type PhaseEventRow =
  | { kind: 'message'; id: string; timestamp: string; role: string; speaker: string; text: string }
  | { kind: 'agent-selected'; id: string; timestamp: string; agentName: string }
  | { kind: 'agent-running'; id: string; timestamp: string; agentName: string; llmModel?: string }
  | { kind: 'agent-finished'; id: string; timestamp: string; agentName: string }
  | { kind: 'case-status'; id: string; timestamp: string; status: string }
  | { kind: 'tool'; id: string; timestamp: string; call: ToolCallSummary }
  | { kind: 'warn'; id: string; timestamp: string; message: string }
  | { kind: 'error'; id: string; timestamp: string; message: string }
  | { kind: 'intention'; id: string; timestamp: string; toolName: string; intention: string; failed: boolean }
  | { kind: 'question'; id: string; timestamp: string; agentName: string; question: string; questionType: string }
  | { kind: 'answer'; id: string; timestamp: string; answer: string }
  | { kind: 'unknown'; id: string; timestamp: string; type: string; payload: string }

/**
 * Project a flat list of CaseEvents into display rows.
 *
 * ToolRequestEvent and ToolResponseEvent are correlated by toolRequestId and
 * emitted as a single 'tool' row at the position of the first seen event.
 * Chronological order is preserved; the list is sorted defensively by timestamp.
 */
export function projectPhaseEventRows(events: CaseEvent[]): PhaseEventRow[] {
  // Defensive sort by timestamp — events from the REST endpoint are ordered chronologically
  // by the backend, but we sort here in case of any ordering inconsistency.
  const sorted = [...events].sort((a, b) => {
    const ta = a.timestamp ? Date.parse(a.timestamp) : 0
    const tb = b.timestamp ? Date.parse(b.timestamp) : 0
    return ta - tb
  })

  // Pass 1: build complete tool call map (request + optional response)
  const toolCallMap = new Map<string, { req?: ToolRequestEvent; res?: ToolResponseEvent }>()
  for (const e of sorted) {
    if (e.type === 'ToolRequestEvent') {
      const req = e as ToolRequestEvent
      const key = req.toolRequestId ?? req.id
      const existing = toolCallMap.get(key) ?? {}
      toolCallMap.set(key, { ...existing, req })
    } else if (e.type === 'ToolResponseEvent') {
      const res = e as ToolResponseEvent
      const key = res.toolRequestId ?? res.id
      const existing = toolCallMap.get(key) ?? {}
      toolCallMap.set(key, { ...existing, res })
    }
  }

  // Pass 2: emit rows in chronological order, deduplicating tool entries
  const rows: PhaseEventRow[] = []
  const seenToolIds = new Set<string>()

  for (const e of sorted) {
    switch (e.type) {
      case 'MessageEvent': {
        const msg = e as CaseMessageEvent
        const text = extractMessageText(msg)
        const role = msg.actor?.role ?? 'UNKNOWN'
        const speaker = msg.actor?.displayName || role
        rows.push({ kind: 'message', id: e.id, timestamp: e.timestamp, role, speaker, text })
        break
      }
      case 'AgentSelectedEvent': {
        const ev = e as AgentSelectedEvent
        rows.push({ kind: 'agent-selected', id: e.id, timestamp: e.timestamp, agentName: ev.agentName })
        break
      }
      case 'AgentRunningEvent': {
        const ev = e as AgentRunningEvent
        rows.push({
          kind: 'agent-running',
          id: e.id,
          timestamp: e.timestamp,
          agentName: ev.agentName,
          llmModel: ev.llmModel,
        })
        break
      }
      case 'AgentFinishedEvent': {
        const ev = e as AgentFinishedEvent
        rows.push({ kind: 'agent-finished', id: e.id, timestamp: e.timestamp, agentName: ev.agentName })
        break
      }
      case 'CaseStatusEvent': {
        const ev = e as CaseStatusEvent
        rows.push({ kind: 'case-status', id: e.id, timestamp: e.timestamp, status: ev.status })
        break
      }
      case 'ToolRequestEvent':
      case 'ToolResponseEvent': {
        const key = (e as ToolRequestEvent | ToolResponseEvent).toolRequestId ?? e.id
        if (!seenToolIds.has(key)) {
          seenToolIds.add(key)
          const { req, res } = toolCallMap.get(key) ?? {}
          const toolName = req?.toolName ?? res?.toolName ?? 'unknown'
          const args = req?.args ?? null
          const success = res?.success ?? null
          const durationMs = res?.durationMs ?? null
          const outputPreview = res ? extractToolOutput(res) : null
          rows.push({
            kind: 'tool',
            id: e.id,
            timestamp: e.timestamp,
            call: { requestId: key, toolName, args, success, durationMs, outputPreview },
          })
        }
        break
      }
      case 'WarnEvent': {
        const ev = e as WarnEvent
        rows.push({ kind: 'warn', id: e.id, timestamp: e.timestamp, message: ev.message })
        break
      }
      case 'ErrorEvent': {
        const ev = e as ErrorEvent
        rows.push({ kind: 'error', id: e.id, timestamp: e.timestamp, message: ev.message })
        break
      }
      case 'IntentionGeneratedEvent': {
        const ev = e as IntentionGeneratedEvent
        rows.push({
          kind: 'intention',
          id: e.id,
          timestamp: e.timestamp,
          toolName: ev.toolName,
          intention: ev.intention,
          failed: ev.isFailedIntention,
        })
        break
      }
      case 'QuestionEvent': {
        const ev = e as QuestionEvent
        rows.push({
          kind: 'question',
          id: e.id,
          timestamp: e.timestamp,
          agentName: ev.agentName,
          question: ev.question,
          questionType: ev.questionType,
        })
        break
      }
      case 'AnswerEvent': {
        const ev = e as AnswerEvent
        rows.push({ kind: 'answer', id: e.id, timestamp: e.timestamp, answer: ev.answer })
        break
      }
      default: {
        // Generic fallback: expose type, timestamp, and a safe compact payload
        const unknown = e as unknown as Record<string, unknown>
        const payload = safePayloadPreview(unknown)
        rows.push({
          kind: 'unknown',
          id: e.id,
          timestamp: e.timestamp,
          type: (e as { type: string }).type ?? 'UnknownEvent',
          payload,
        })
        break
      }
    }
  }

  return rows
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function extractMessageText(event: CaseMessageEvent): string {
  return (
    event.content
      ?.filter((c): c is Text => 'content' in c)
      .map((c) => c.content)
      .join('') ?? ''
  )
}

function extractToolOutput(res: ToolResponseEvent): string | null {
  const output = res.output as { content?: string } | null
  if (!output) return null
  return output.content ?? null
}

/**
 * Produce a compact, safe string preview of an unknown event payload.
 * Omits known base fields (id, caseId, namespaceId, metadata, timestamp, type)
 * to reduce noise; truncates at 300 chars.
 */
function safePayloadPreview(event: Record<string, unknown>): string {
  const BASE_KEYS = new Set(['id', 'caseId', 'namespaceId', 'metadata', 'timestamp', 'type'])
  const extra: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(event)) {
    if (!BASE_KEYS.has(k)) extra[k] = v
  }
  try {
    const json = JSON.stringify(extra)
    return json.length > 300 ? json.slice(0, 297) + '…' : json
  } catch {
    return '[unserializable]'
  }
}
