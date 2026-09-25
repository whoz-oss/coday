/**
 * Translation of raw AgentOS event DTOs into the Factory domain vocabulary.
 *
 * Everything in this module is pure: given the same event history it produces
 * the same domain events and metrics. Chronological order is established by the
 * backend (`ORDER BY timestamp ASC, id ASC`), so slicing anchors on the event
 * id and never on a timestamp string comparison — Jackson serializes `Instant`
 * with a variable number of decimals, which makes lexical comparison silently
 * wrong.
 */

import type { RuntimeEvent, RuntimeModelUsage } from '../../ports/agent-runtime-gateway.js'
import type { CaseEventDTO, MessageContentDTO } from './agentos-dtos.js'

/**
 * Event shape with the typed fields this translator reads.
 *
 * `CaseEventDTO` keeps an open index signature, so it is structurally assignable
 * to this richer view without trusting the wire format.
 */
export interface RawCaseEvent extends CaseEventDTO {
  status?: unknown
  actor?: { role?: unknown; [key: string]: unknown } | null
  content?: MessageContentDTO[] | string | null
  question?: unknown
  questionId?: unknown
  answer?: unknown
  agentName?: unknown
  llmProvider?: unknown
  llmModel?: unknown
  toolName?: unknown
  success?: unknown
}

/** Event type carrying the case status. */
export const CASE_STATUS_EVENT = 'CaseStatusEvent'

/** Statuses marking quiescence: nothing more happens without new input. */
export const QUIESCENT_STATUSES: readonly string[] = ['IDLE', 'KILLED', 'ERROR']

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

/**
 * Returns the events that follow an event identified by its id.
 *
 * `anchored` is false when the reference id cannot be found (deleted event or
 * truncated history): the result is then the full list, which is permissive and
 * must be surfaced, never silently ignored.
 */
export function sliceAfterId(
  events: RawCaseEvent[],
  baselineId?: string | null
): { events: RawCaseEvent[]; anchored: boolean } {
  if (!baselineId) return { events, anchored: true }
  const index = events.findIndex((e) => e.id === baselineId)
  if (index < 0) return { events, anchored: false }
  return { events: events.slice(index + 1), anchored: true }
}

/** Finds the first status event carrying one of the requested statuses. */
export function findStatusEvent(
  events: RawCaseEvent[],
  statuses: readonly string[],
  fromIndex = 0
): { event: RawCaseEvent; index: number } | null {
  for (let i = fromIndex; i < events.length; i++) {
    const e = events[i]
    if (e && e.type === CASE_STATUS_EVENT && typeof e.status === 'string' && statuses.includes(e.status)) {
      return { event: e, index: i }
    }
  }
  return null
}

/** Finds the LAST status event carrying one of the requested statuses (F7). */
export function findLastStatusEvent(
  events: RawCaseEvent[],
  statuses: readonly string[]
): { event: RawCaseEvent; index: number } | null {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]
    if (e && e.type === CASE_STATUS_EVENT && typeof e.status === 'string' && statuses.includes(e.status)) {
      return { event: e, index: i }
    }
  }
  return null
}

/**
 * Returns the `QuestionEvent`s without a matching `AnswerEvent`.
 *
 * Pairing uses `AnswerEvent.questionId`, a model field, not a timestamp
 * comparison. The full case history must be passed, not just the current turn.
 */
export function findUnansweredQuestions(allEvents: RawCaseEvent[]): RawCaseEvent[] {
  const answered = new Set(
    allEvents
      .filter((e) => e.type === 'AnswerEvent')
      .map((e) => e.questionId)
      .filter((id): id is string => typeof id === 'string')
  )
  return allEvents.filter((e) => e.type === 'QuestionEvent' && !answered.has(e.id))
}

/** Names of the workers actually selected during the turn, in order, deduplicated. */
export function collectAgentsSelected(events: RawCaseEvent[]): string[] {
  const names = events
    .filter((e) => e.type === 'AgentSelectedEvent')
    .map((e) => e.agentName)
    .filter((name): name is string => typeof name === 'string' && name.length > 0)
  return [...new Set(names)]
}

/** Counts events of a given wire type. */
export function countType(events: RawCaseEvent[], type: string): number {
  return events.filter((e) => e.type === type).length
}

function extractMessageContent(content: RawCaseEvent['content']): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content.map((part) => (typeof part?.content === 'string' ? part.content : '')).join('')
}

/** Text of the last agent `MessageEvent` of the turn. */
export function extractLastAgentMessage(events: RawCaseEvent[]): string {
  const last = events.filter((e) => e.type === 'MessageEvent' && e.actor?.role === 'AGENT').at(-1)
  if (!last) return ''
  return extractMessageContent(last.content)
}

/**
 * LLM models used during the turn, deduplicated.
 *
 * `AgentRunningEvent` is collected alongside `AgentFinishedEvent`: a turn killed
 * by budget emits no `AgentFinishedEvent` but has already emitted the running
 * one, which is exactly when knowing the model matters most. A field that is
 * null is reported as null, never replaced by an invented default.
 */
export function collectLlmModels(events: RawCaseEvent[]): RuntimeModelUsage[] {
  const seen = new Set<string>()
  const result: RuntimeModelUsage[] = []

  for (const e of events) {
    if (e.type !== 'AgentRunningEvent' && e.type !== 'AgentFinishedEvent') continue
    const llmProvider = asString(e.llmProvider)
    const llmModel = asString(e.llmModel)
    if (llmProvider == null && llmModel == null) continue

    const agentName = asString(e.agentName)
    const key = JSON.stringify({ agentName, llmProvider, llmModel })
    if (seen.has(key)) continue
    seen.add(key)
    result.push({ agentName, llmProvider, llmModel })
  }

  return result
}

/** Failed tool calls grouped by tool name. */
export function buildFailedToolCalls(toolResponseEvents: RawCaseEvent[]): Record<string, number> {
  const result: Record<string, number> = {}
  for (const e of toolResponseEvents) {
    if (e.success === false) {
      const name = asString(e.toolName) ?? 'unknown'
      result[name] = (result[name] ?? 0) + 1
    }
  }
  return result
}

/** Translates a single raw DTO into a domain event. */
export function toRuntimeEvent(event: RawCaseEvent): RuntimeEvent {
  const base = {
    id: event.id,
    type: event.type,
    ...(typeof event.timestamp === 'string' ? { timestamp: event.timestamp } : {}),
  }

  switch (event.type) {
    case 'CaseStatusEvent':
      return { ...base, kind: 'status', status: asString(event.status) ?? '' }
    case 'MessageEvent':
      return {
        ...base,
        kind: 'message',
        role: asString(event.actor?.role),
        content: extractMessageContent(event.content),
      }
    case 'QuestionEvent':
      return { ...base, kind: 'question', questionId: event.id, question: asString(event.question) ?? '' }
    case 'AnswerEvent':
      return { ...base, kind: 'answer', questionId: asString(event.questionId), answer: asString(event.answer) }
    case 'AgentSelectedEvent':
      return { ...base, kind: 'worker_selected', workerName: asString(event.agentName) }
    case 'AgentFinishedEvent':
      return {
        ...base,
        kind: 'worker_finished',
        workerName: asString(event.agentName),
        llmProvider: asString(event.llmProvider),
        llmModel: asString(event.llmModel),
      }
    case 'AgentRunningEvent':
      return {
        ...base,
        kind: 'worker_running',
        workerName: asString(event.agentName),
        llmProvider: asString(event.llmProvider),
        llmModel: asString(event.llmModel),
      }
    case 'ToolResponseEvent':
      return {
        ...base,
        kind: 'tool_response',
        toolName: asString(event.toolName),
        success: typeof event.success === 'boolean' ? event.success : null,
      }
    default:
      return { ...base, kind: 'other' }
  }
}

/** Translates a raw event history into domain events, preserving order. */
export function toRuntimeEvents(events: RawCaseEvent[]): RuntimeEvent[] {
  return events.map(toRuntimeEvent)
}
