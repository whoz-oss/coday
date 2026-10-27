import { ReasoningEffort } from '@whoz-oss/agentos-api-client'

/** One entry of the /effort picker. A null effort removes the case override. */
export interface EffortOption {
  effort: ReasoningEffort | null
  label: string
  description: string
}

const COMMAND = 'effort'

export const EFFORT_OPTIONS: readonly EffortOption[] = [
  { effort: null, label: 'default', description: 'Each agent uses the effort of its model' },
  { effort: ReasoningEffort.NONE, label: 'none', description: 'No reasoning' },
  { effort: ReasoningEffort.LOW, label: 'low', description: 'Quick answers, light reasoning' },
  { effort: ReasoningEffort.MEDIUM, label: 'medium', description: 'Balanced' },
  { effort: ReasoningEffort.HIGH, label: 'high', description: 'Deeper reasoning' },
  { effort: ReasoningEffort.XHIGH, label: 'xhigh', description: 'Very deep reasoning' },
  { effort: ReasoningEffort.MAX, label: 'max', description: 'Maximum reasoning, slowest and most costly' },
]

/**
 * The /effort options to suggest for a composer value, or null when the value is not an /effort command.
 *
 * Two letters of the command are enough to list every level ("/ef"), and a level prefix after a space
 * filters them ("/effort h").
 */
export function effortOptionsFor(value: string): EffortOption[] | null {
  const match = /^\/(\S+)(\s+(\S*))?$/.exec(value)
  if (!match) return null
  const command = (match[1] ?? '').toLowerCase()
  if (match[2] === undefined) {
    return command.length >= 2 && COMMAND.startsWith(command) ? [...EFFORT_OPTIONS] : null
  }
  if (command !== COMMAND) return null
  const level = (match[3] ?? '').toLowerCase()
  return EFFORT_OPTIONS.filter((option) => option.label.startsWith(level))
}

/**
 * For a submitted value: the option an "/effort <level>" command sets, null when the level is missing or
 * unknown, and undefined when the value is not an /effort command (so it is a message for the agent).
 */
export function parseEffortCommand(value: string): EffortOption | null | undefined {
  const match = /^\/effort(?:\s+(\S+))?$/i.exec(value.trim())
  if (!match) return undefined
  const level = match[1]?.toLowerCase()
  return EFFORT_OPTIONS.find((option) => option.label === level) ?? null
}

/** How the case-level effort reads in the case window. */
export function effortLabel(effort: ReasoningEffort | null | undefined): string {
  return EFFORT_OPTIONS.find((option) => option.effort === (effort ?? null))?.label ?? 'default'
}
