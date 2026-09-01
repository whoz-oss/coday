import { FactoryRunPhase } from '../../services/factory-api.service'
import { PhaseEventRow } from './factory-phase-events.utils'

export interface FactEntry {
  key: string
  value: string
  chips?: string[]
}
export interface FactSection {
  title: string
  entries: FactEntry[]
}
export interface PhaseEvidence {
  command?: string
  tools?: string
  files?: string
  log?: string
}
export interface ConversationEntry {
  speaker: string
  content: string
}

/** Extracted brief (first USER message) and agent response (last AGENT message). */
export interface PhaseBriefResponse {
  brief: string | null
  agentResponse: string | null
}

/** A single reviewer's outcome from an adversarial-review phase. */
export interface ReviewOutcome {
  reviewerName: string
  verdict: 'PASS' | 'FAIL' | 'SKIP' | string
  hasCritical: boolean
  summary: string | null
  caseId: string | null
  /** Additional raw fields not covered by the primary fields, serialised for fallback display. */
  extra: string | null
}

/**
 * Result of projecting phase.facts.outcomes.
 *
 * valid  — well-formed array of reviewer outcomes
 * empty  — outcomes key present but array is empty
 * absent — outcomes key not present in facts
 * malformed — key present but value is not a recognisable outcomes array; raw fallback provided
 */
export type ReviewOutcomesProjection =
  | { kind: 'valid'; outcomes: ReviewOutcome[] }
  | { kind: 'empty' }
  | { kind: 'absent' }
  | { kind: 'malformed'; raw: string }

const groups: Array<[string, string[]]> = [
  ['Outcome', ['exitCode', 'timedOut', 'tasks', 'domain', 'claimsMatch']],
  ['Context', ['agentName', 'agentsSelected', 'caseId', 'rootPath', 'ticketId', 'summary']],
]
const evidenceKeys = new Set([
  'command',
  'cmd',
  'tools',
  'toolsUsed',
  'filesModified',
  'filesUntracked',
  'plannedFiles',
  'actualFiles',
  'files',
  'log',
  'logs',
  'conversation',
  'messages',
])

/**
 * Keys handled by dedicated projections — excluded from generic "Other recorded facts".
 * fetch-ticket facts are surfaced in the ticket section, not the generic DL.
 */
const dedicatedKeys = new Set([
  'outcomes',
  'ticketId',
  'summary',
  'fieldCount',
  'commentCount',
  'commentsIncluded',
  'commentsTruncated',
])

export function displayValue(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return `${value}`
  if (Array.isArray(value)) return value.map(displayValue).join(', ')
  if (value !== null && typeof value === 'object') {
    try {
      return JSON.stringify(value, null, 2)
    } catch {
      return '[object]'
    }
  }
  return 'Recorded value'
}

/**
 * Render a generic fact value for display in a DL entry.
 * Primitive values are returned as-is.
 * Arrays are joined with commas.
 * Objects are formatted as indented JSON, bounded to 40 lines.
 */
export function displayValueBounded(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return `${value}`
  if (Array.isArray(value)) {
    if (value.every((v) => typeof v !== 'object' || v === null)) {
      return value.map(displayValue).join(', ')
    }
    try {
      const json = JSON.stringify(value, null, 2)
      return truncateLines(json, 40)
    } catch {
      return '[array]'
    }
  }
  if (value !== null && typeof value === 'object') {
    try {
      const json = JSON.stringify(value, null, 2)
      return truncateLines(json, 40)
    } catch {
      return '[object]'
    }
  }
  return 'Recorded value'
}

function truncateLines(text: string, maxLines: number): string {
  const lines = text.split('\n')
  if (lines.length <= maxLines) return text
  return lines.slice(0, maxLines).join('\n') + `\n… (${lines.length - maxLines} more lines)`
}

function taskChips(value: unknown): string[] | undefined {
  if (Array.isArray(value)) return value.map(displayValue)
  if (!value || typeof value !== 'object') return undefined
  return Object.entries(value as Record<string, unknown>)
    .filter(([, entry]) => entry !== null && entry !== undefined && typeof entry !== 'object')
    .map(([key, entry]) => `${key}: ${displayValue(entry)}`)
}

export function projectPhaseFacts(phase: FactoryRunPhase): FactSection[] {
  const remaining = new Map(Object.entries(phase.facts ?? {}))
  for (const key of evidenceKeys) remaining.delete(key)
  for (const key of dedicatedKeys) remaining.delete(key)
  const sections = groups
    .map(([title, keys]) => ({
      title,
      entries: keys.flatMap((key) => {
        const value = remaining.get(key)
        remaining.delete(key)
        return value === undefined
          ? []
          : [
              {
                key,
                value: displayValue(value),
                ...(key === 'tasks' && taskChips(value) ? { chips: taskChips(value) } : {}),
              },
            ]
      }),
    }))
    .filter((section) => section.entries.length)
  if (remaining.size)
    sections.push({
      title: 'Other recorded facts',
      entries: [...remaining].map(([key, value]) => ({ key, value: displayValueBounded(value) })),
    })
  return sections
}

export function projectPhaseEvidence(phase: FactoryRunPhase): PhaseEvidence {
  const facts = phase.facts ?? {}
  const value = (...keys: string[]): string | undefined => {
    const found = keys.map((key) => facts[key]).find((item) => item !== undefined && item !== null && item !== '')
    return found === undefined ? undefined : displayValue(found)
  }
  return {
    command: value('command', 'cmd'),
    tools: value('tools', 'toolsUsed'),
    files: value('filesModified', 'filesUntracked', 'plannedFiles', 'actualFiles', 'files'),
    log: value('log', 'logs'),
  }
}

export function projectConversation(phase: FactoryRunPhase): ConversationEntry[] {
  const raw = phase.facts?.['conversation'] ?? phase.facts?.['messages']
  if (!Array.isArray(raw)) return []
  return raw.flatMap((entry): ConversationEntry[] => {
    if (!entry || typeof entry !== 'object') return []
    const record = entry as Record<string, unknown>
    const content = record['content'] ?? record['text'] ?? record['message']
    if (typeof content !== 'string' || !content) return []
    const speaker = record['role'] ?? record['speaker'] ?? record['agent']
    return [{ speaker: typeof speaker === 'string' && speaker ? speaker : 'Recorded message', content }]
  })
}

/**
 * Project phase.facts.outcomes into a typed ReviewOutcomesProjection.
 *
 * Each item is expected to carry at minimum `reviewerName` (or `reviewer`).
 * A missing or non-array value is surfaced as `malformed` rather than silently dropped.
 */
export function projectReviewOutcomes(phase: FactoryRunPhase): ReviewOutcomesProjection {
  const raw = phase.facts?.['outcomes']

  if (raw === undefined || raw === null) return { kind: 'absent' }

  if (!Array.isArray(raw)) {
    let fallback: string
    try {
      fallback = JSON.stringify(raw, null, 2)
    } catch {
      fallback = String(raw)
    }
    return { kind: 'malformed', raw: fallback }
  }

  if (raw.length === 0) return { kind: 'empty' }

  // Attempt to parse each element. If none have a recognisable reviewer field, treat as malformed.
  const outcomes: ReviewOutcome[] = []
  let atLeastOneValid = false

  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const record = item as Record<string, unknown>

    const reviewerName =
      (typeof record['reviewerName'] === 'string' ? record['reviewerName'] : null) ??
      (typeof record['reviewer'] === 'string' ? record['reviewer'] : null) ??
      null

    if (!reviewerName) continue

    atLeastOneValid = true

    const rawVerdict = record['verdict'] ?? record['status']
    const verdict =
      typeof rawVerdict === 'string' && rawVerdict.length > 0
        ? (rawVerdict.toUpperCase() as ReviewOutcome['verdict'])
        : 'SKIP'

    const hasCritical = record['hasCritical'] === true

    const rawSummary = record['summary'] ?? record['findings']
    const summary = typeof rawSummary === 'string' && rawSummary.length > 0 ? rawSummary : null

    const rawCaseId = record['caseId']
    const caseId = typeof rawCaseId === 'string' && rawCaseId.length > 0 ? rawCaseId : null

    // Collect unrecognised fields for a compact fallback display
    const knownKeys = new Set([
      'reviewerName',
      'reviewer',
      'verdict',
      'status',
      'hasCritical',
      'summary',
      'findings',
      'caseId',
    ])
    const extraEntries: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(record)) {
      if (!knownKeys.has(k)) extraEntries[k] = v
    }
    let extra: string | null = null
    if (Object.keys(extraEntries).length > 0) {
      try {
        extra = JSON.stringify(extraEntries, null, 2)
      } catch {
        extra = null
      }
    }

    outcomes.push({ reviewerName, verdict, hasCritical, summary, caseId, extra })
  }

  if (!atLeastOneValid) {
    // Array present but no item has a recognisable reviewer field
    let fallback: string
    try {
      fallback = JSON.stringify(raw, null, 2)
    } catch {
      fallback = '[unserializable]'
    }
    return { kind: 'malformed', raw: fallback }
  }

  return { kind: 'valid', outcomes }
}

/**
 * Project brief (first USER message) and agent response (last AGENT message)
 * from phase-recorded conversation entries (phase.facts.messages / .conversation).
 *
 * Returns null for each field when no matching entry exists.
 */
export function projectPhaseBriefFromFacts(phase: FactoryRunPhase): PhaseBriefResponse {
  const entries = projectConversation(phase)
  const userEntry = entries.find((e) => {
    const r = e.speaker.toLowerCase()
    return r === 'user' || r === 'human'
  })
  // Last agent/assistant entry
  const agentEntries = entries.filter((e) => {
    const r = e.speaker.toLowerCase()
    return r === 'agent' || r === 'assistant' || r === 'ai'
  })
  return {
    brief: userEntry?.content ?? null,
    agentResponse: agentEntries.at(-1)?.content ?? null,
  }
}

// ---------------------------------------------------------------------------
// fetch-ticket phase projection
// ---------------------------------------------------------------------------

/**
 * Metadata facts recorded by the fetch-ticket phase.
 * Only the metadata is persisted; ticketContent is not (registry invariant 2).
 */
export interface FetchTicketInfo {
  ticketId: string
  summary: string | null
  fieldCount: number | null
  commentCount: number | null
  commentsIncluded: number | null
  commentsTruncated: boolean | null
}

/**
 * Extract fetch-ticket metadata from phase facts.
 * Returns null when this is not a fetch-ticket phase (ticketId absent).
 */
export function projectFetchTicketInfo(phase: FactoryRunPhase): FetchTicketInfo | null {
  const facts = phase.facts ?? {}
  const ticketId = facts['ticketId']
  if (typeof ticketId !== 'string' || !ticketId) return null

  const toNumber = (v: unknown): number | null => (typeof v === 'number' ? v : null)
  const toBoolean = (v: unknown): boolean | null => (typeof v === 'boolean' ? v : null)
  const toString = (v: unknown): string | null => (typeof v === 'string' && v ? v : null)

  return {
    ticketId,
    summary: toString(facts['summary']),
    fieldCount: toNumber(facts['fieldCount']),
    commentCount: toNumber(facts['commentCount']),
    commentsIncluded: toNumber(facts['commentsIncluded']),
    commentsTruncated: toBoolean(facts['commentsTruncated']),
  }
}

/**
 * Project brief and agent response from live event rows.
 *
 * Used as the primary source when events are loaded (more reliable, includes
 * the full content from AgentOS case events).
 * Falls back to null when the event list has no matching messages.
 */
export function projectBriefResponseFromEvents(rows: PhaseEventRow[]): PhaseBriefResponse {
  const messageRows = rows.filter((r): r is Extract<PhaseEventRow, { kind: 'message' }> => r.kind === 'message')
  const userMsg = messageRows.find((r) => r.role === 'USER')
  const agentMessages = messageRows.filter((r) => r.role === 'AGENT')
  return {
    brief: userMsg?.text ?? null,
    agentResponse: agentMessages.at(-1)?.text ?? null,
  }
}
