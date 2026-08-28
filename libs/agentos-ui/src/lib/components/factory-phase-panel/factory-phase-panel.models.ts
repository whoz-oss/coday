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

function displayValue(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return `${value}`
  if (Array.isArray(value)) return value.map(displayValue).join(', ')
  return 'Recorded value'
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
      entries: [...remaining].map(([key, value]) => ({ key, value: displayValue(value) })),
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
