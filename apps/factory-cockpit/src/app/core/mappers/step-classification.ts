/**
 * Step classification: actor kind, lane descriptor, step state and duration.
 * Pure functions, no Angular, no HTTP.
 */

import { TimelineStepStatus, Tone } from '../models'
import { asObject, getString, toEpochMs } from './utils'

const CODE_HINTS = /(oracle|build|test|lint|scan|typecheck|type-check|compile|deterministic|verify|code)/i
const HUMAN_HINTS = /(human|humain|approv|validat|decision|manual|confirm|review|gate|revue)/i

export type ActorKind = 'human' | 'agent' | 'code'

export interface LaneDescriptor {
  id: string
  kind: ActorKind
  label: string
}

export type StepState = TimelineStepStatus

/** Resolve the actor kind of a step (explicit lane \u2192 responsibility \u2192 hints). */
export function classifyActorKind(step: unknown): ActorKind {
  const obj = asObject(step)
  const lane = getString(obj, 'lane')
  if (lane === 'human' || lane === 'agent' || lane === 'code') return lane
  const responsibility = asObject(obj?.['responsibility'])
  const kind = getString(responsibility, 'kind')
  if (kind === 'human' || kind === 'agent' || kind === 'code') return kind
  const haystack = `${getString(obj, 'id') ?? ''} ${getString(obj, 'name') ?? ''}`
  if (CODE_HINTS.test(haystack)) return 'code'
  if (HUMAN_HINTS.test(haystack)) return 'human'
  return 'agent'
}

/** Resolve the display name of the actor behind a step. */
export function actorName(step: unknown): string {
  const obj = asObject(step)
  const responsibility = asObject(obj?.['responsibility'])
  const facts = asObject(obj?.['facts'])
  return getString(responsibility, 'name') ?? getString(obj, 'agentName') ?? getString(facts, 'agentName') ?? 'agent'
}

/** Classify a step into a concrete lane (human \u2192 engineer, code, agent per name). */
export function classifyLane(step: unknown): LaneDescriptor {
  const kind = classifyActorKind(step)
  if (kind === 'human') return { id: 'engineer', kind: 'human', label: 'engineer' }
  if (kind === 'code') return { id: 'code', kind: 'code', label: 'code' }
  const name = actorName(step)
  return { id: `agent:${name}`, kind: 'agent', label: name }
}

/** Preserve the authoritative Factory step status for truthful rendering. */
export function resolveStepState(step: unknown): StepState {
  const status = getString(asObject(step), 'status') ?? 'pending'
  return (
    ['pending', 'ready', 'running', 'waiting_human', 'completed', 'failed', 'indeterminate', 'cancelled'] as const
  ).includes(status as TimelineStepStatus)
    ? (status as TimelineStepStatus)
    : 'pending'
}

/**
 * Duration of a step in seconds, or `null` when the projection does not provide it.
 * Absolute bounds are authoritative: finishedAt → completedAt → endedAt.
 * durationMs is only a fallback when a complete, valid interval is unavailable.
 */
export function stepDurationSec(step: unknown): number | null {
  const obj = asObject(step)
  const start = toEpochMs(obj?.['startedAt'])
  const end = toEpochMs(obj?.['finishedAt'] ?? obj?.['completedAt'] ?? obj?.['endedAt'])
  if (start !== null && end !== null && end >= start) return (end - start) / 1000
  const raw = obj?.['durationMs']
  const durationMs = typeof raw === 'number' && Number.isFinite(raw) && raw >= 0 ? raw : undefined
  return durationMs !== undefined ? durationMs / 1000 : null
}

export function toneForActor(kind: ActorKind): Tone {
  if (kind === 'human') return 'amber'
  if (kind === 'code') return 'cyan'
  return 'violet'
}
