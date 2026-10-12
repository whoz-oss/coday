/**
 * Timeline lane construction, phase-bar segments and run summary mapping.
 * Transforms a workflow projection snapshot into visual timeline lanes.
 */

import {
  AgentAttempt,
  PhaseSegment,
  RunStatus,
  RunSummary,
  TimelineBlock,
  TimelineLane,
  TimelineStepStatus,
  Tone,
} from '../models'
import { asArray, asObject, getString, getNumber, toEpochMs } from './utils'
import {
  classifyActorKind,
  classifyLane,
  actorName,
  resolveStepState,
  stepDurationSec,
  toneForActor,
} from './step-classification'
import { extractAttempts, extractRealCost } from './actions'

const MIN_BLOCK_SEC = 2
const TERMINAL_ATTEMPT_STATUSES = new Set([
  'succeeded',
  'completed',
  'failed',
  'indeterminate',
  'interrupted',
  'superseded',
  'cancelled',
])

// ---------------------------------------------------------------------------
// Run status
// ---------------------------------------------------------------------------

function deriveStatusFromSteps(steps: unknown[]): RunStatus {
  if (steps.length === 0) return 'queued'
  const states = steps.map((step) => resolveStepState(step))
  if (states.some((state) => state === 'failed' || state === 'indeterminate' || state === 'cancelled')) return 'failed'
  if (states.some((state) => state === 'running' || state === 'waiting_human')) return 'running'
  if (states.every((state) => state === 'completed')) return 'succeeded'
  return 'queued'
}

/**
 * Map the backend workflow/step status to the cockpit {@link RunStatus}.
 */
export function mapWorkflowStateToRunStatus(state?: string, steps?: unknown[]): RunStatus {
  const normalized = (state ?? '').toLowerCase()
  switch (normalized) {
    case 'running':
    case 'waiting_human':
      return 'running'
    case 'ready':
      return 'queued'
    case 'active':
    case 'existing':
      return deriveStatusFromSteps(asArray(steps))
    case 'completed':
    case 'succeeded':
      return 'succeeded'
    case 'failed':
    case 'indeterminate':
    case 'cancelled':
      return 'failed'
    case 'pending':
    case 'queued':
      return 'queued'
    default:
      return deriveStatusFromSteps(asArray(steps))
  }
}

// ---------------------------------------------------------------------------
// Normalized engineer request
// ---------------------------------------------------------------------------

export interface ControllerRequest {
  text?: string
  prompt?: string
  namespaceId?: string
  observedAt?: string
  actorId?: string
  source?: string
}

export function readControllerRequest(
  snapshot: Record<string, unknown>,
  projection: Record<string, unknown>
): ControllerRequest | undefined {
  const instance = asObject(snapshot['instance'])
  const raw = snapshot['controllerRequest'] ?? projection['controllerRequest'] ?? instance?.['controllerRequest']
  if (typeof raw === 'string' && raw.length > 0) return { text: raw }
  const obj = asObject(raw)
  if (!obj) return undefined
  const request: ControllerRequest = {}
  const text = getString(obj, 'text')
  if (text) request.text = text
  const prompt = getString(obj, 'prompt')
  if (prompt) request.prompt = prompt
  const namespaceId = getString(obj, 'namespaceId')
  if (namespaceId) request.namespaceId = namespaceId
  const observedAt = getString(obj, 'observedAt')
  if (observedAt) request.observedAt = observedAt
  const actorId = getString(obj, 'actorId')
  if (actorId) request.actorId = actorId
  const source = getString(obj, 'source')
  if (source) request.source = source
  const hasContent =
    request.text !== undefined ||
    request.prompt !== undefined ||
    request.actorId !== undefined ||
    request.observedAt !== undefined
  return hasContent ? request : undefined
}

// ---------------------------------------------------------------------------
// Attempt comparison & status
// ---------------------------------------------------------------------------

function compareAttempts(left: AgentAttempt, right: AgentAttempt): number {
  const numberOrder = left.attemptNumber - right.attemptNumber
  if (numberOrder !== 0) return numberOrder
  const leftTime = toEpochMs(left.startedAt ?? left.createdAt ?? left.completedAt) ?? Number.POSITIVE_INFINITY
  const rightTime = toEpochMs(right.startedAt ?? right.createdAt ?? right.completedAt) ?? Number.POSITIVE_INFINITY
  return leftTime - rightTime || left.attemptId.localeCompare(right.attemptId)
}

function attemptStepStatus(attempt: AgentAttempt): TimelineStepStatus {
  const status = attempt.status.toLowerCase()
  if (status === 'succeeded' || status === 'completed' || status === 'superseded') return 'completed'
  if (status === 'interrupted' || status === 'cancelled') return 'cancelled'
  if (status === 'failed' || status === 'indeterminate' || status === 'running' || status === 'waiting_human')
    return status
  return 'pending'
}

/**
 * Replace agent steps with their durable execution attempts for the timeline.
 */
export function timelineSteps(steps: unknown[], attempts: AgentAttempt[]): unknown[] {
  const byStep = new Map<string, AgentAttempt[]>()
  for (const attempt of attempts) {
    if (!attempt.stepId) continue
    const history = byStep.get(attempt.stepId) ?? []
    history.push(attempt)
    byStep.set(attempt.stepId, history)
  }

  return steps.flatMap((step) => {
    const obj = asObject(step) ?? {}
    const stepId = getString(obj, 'id')
    const history = stepId ? byStep.get(stepId) : undefined
    if (classifyActorKind(obj) !== 'agent' || !history?.length) return [step]

    const ordered = [...history].sort(compareAttempts)
    return ordered.map((attempt) => {
      const terminal = TERMINAL_ATTEMPT_STATUSES.has(attempt.status.toLowerCase())
      return {
        ...obj,
        id: `${stepId}:attempt:${attempt.attemptId}`,
        sourceStepId: stepId,
        name:
          ordered.length > 1
            ? `${getString(obj, 'name') ?? stepId} \u00b7 attempt ${attempt.attemptNumber || '?'}`
            : (getString(obj, 'name') ?? stepId),
        agentName: attempt.agentName || getString(obj, 'agentName'),
        attemptId: attempt.attemptId,
        attemptNumber: attempt.attemptNumber,
        caseId: attempt.caseId || undefined,
        failureCode: attempt.failureCode || undefined,
        responsibility: {
          ...(asObject(obj['responsibility']) ?? {}),
          kind: 'agent',
          name: attempt.agentName || actorName(obj),
        },
        status: attemptStepStatus(attempt),
        startedAt: attempt.startedAt,
        completedAt: terminal ? attempt.completedAt : undefined,
        durationMs: undefined,
      }
    })
  })
}

// ---------------------------------------------------------------------------
// Phase segments (phase bar)
// ---------------------------------------------------------------------------

function phaseSegmentStatus(step: unknown): PhaseSegment['status'] {
  const state = resolveStepState(step)
  if (state === 'waiting_human') return 'waiting_human'
  if (state === 'running') return 'running'
  if (state === 'pending' || state === 'ready') return 'pending'
  if (state === 'failed') return 'failed'
  if (state === 'cancelled') return 'cancelled'
  if (state === 'indeterminate') return 'indeterminate'
  return 'done'
}

function phaseTone(step: unknown): Tone {
  return ['failed', 'indeterminate'].includes(resolveStepState(step)) ? 'red' : toneForActor(classifyActorKind(step))
}

/** Derive the phase-bar segments from the projection steps. */
export function mapStepsToPhaseSegments(steps: unknown[], totalDurationSec = 0): PhaseSegment[] {
  const list = Array.isArray(steps) ? steps : []
  if (list.length === 0) return []
  const durations = list.map((step) => stepDurationSec(step) ?? 0)
  const summed = durations.reduce((sum, d) => sum + d, 0)
  const total = totalDurationSec > 0 ? totalDurationSec : summed
  return list.map((step, index) => {
    const obj = asObject(step)
    const id = getString(obj, 'id') ?? getString(obj, 'name') ?? `step-${index + 1}`
    const name = getString(obj, 'name')
    const duration = durations[index] ?? 0
    const segment: PhaseSegment = {
      key: id,
      ratio: total > 0 ? duration / total : 1 / list.length,
      tone: phaseTone(step),
      status: phaseSegmentStatus(step),
    }
    if (name && name !== id) segment.label = name
    return segment
  })
}

export function currentPhaseName(steps: unknown[]): string | undefined {
  const active = steps.find((step) => ['running', 'waiting_human', 'ready'].includes(resolveStepState(step)))
  if (active) return getString(asObject(active), 'name') ?? getString(asObject(active), 'id')
  const last = steps[steps.length - 1]
  return last ? (getString(asObject(last), 'name') ?? getString(asObject(last), 'id')) : undefined
}

// ---------------------------------------------------------------------------
// Timeline lanes
// ---------------------------------------------------------------------------

function normalizeTicks(values: unknown, t0: number, startSec: number, endSec: number): number[] | undefined {
  const ticks: number[] = []
  for (const value of asArray(values)) {
    let sec: number | null = null
    if (typeof value === 'number' && Number.isFinite(value)) sec = value
    else if (typeof value === 'string') {
      const epoch = toEpochMs(value)
      if (epoch !== null) sec = (epoch - t0) / 1000
    }
    if (sec !== null && sec >= startSec && sec <= endSec) ticks.push(sec)
  }
  return ticks.length > 0 ? ticks : undefined
}

/**
 * Derive the temporal lanes from a projection snapshot (+ optional timing).
 */
export function mapProjectionToLanes(input: unknown, timing?: unknown, attempts?: unknown): TimelineLane[] {
  const snapshot = asObject(input) ?? {}
  const projection = asObject(snapshot['projection']) ?? snapshot
  const steps = timelineSteps(asArray(projection['steps']), extractAttempts(attempts))
  const controllerRequest = readControllerRequest(snapshot, projection)
  if (steps.length === 0 && !controllerRequest) return []

  const timingObj = asObject(timing)
  const stepStarts = steps
    .map((step) => toEpochMs(asObject(step)?.['startedAt']))
    .filter((value): value is number => value !== null)
  const requestStartMs = toEpochMs(controllerRequest?.observedAt)
  const starts = requestStartMs !== null ? [...stepStarts, requestStartMs] : stepStarts
  const t0 = starts.length > 0 ? Math.min(...starts) : (toEpochMs(timingObj?.['startedAt']) ?? Date.now())

  const ordered = steps
    .map((step, index) => {
      const obj = asObject(step) ?? {}
      return { obj, index, start: toEpochMs(obj['startedAt']), duration: stepDurationSec(step) }
    })
    .sort((a, b) => {
      const aStart = a.start ?? Number.POSITIVE_INFINITY
      const bStart = b.start ?? Number.POSITIVE_INFINITY
      return aStart - bStart || a.index - b.index
    })

  const lanes = new Map<string, TimelineLane>()
  const ensureLane = (descriptor: ReturnType<typeof classifyLane>, contextPct?: number): TimelineLane => {
    let lane = lanes.get(descriptor.id)
    if (!lane) {
      lane = {
        id: descriptor.id,
        label: descriptor.label,
        subtitle: descriptor.kind === 'agent' ? descriptor.label : 'workspace',
        kind: descriptor.kind === 'human' ? 'human' : descriptor.kind === 'code' ? 'workspace' : 'agent',
        tone: toneForActor(descriptor.kind),
        blocks: [],
      }
      lanes.set(descriptor.id, lane)
    }
    if (descriptor.kind === 'agent' && contextPct !== undefined) lane.contextPct = contextPct
    return lane
  }

  let cursorSec = 0

  for (const entry of ordered) {
    const descriptor = classifyLane(entry.obj)
    const contextPct = getNumber(entry.obj, 'contextPct')
    const lane = ensureLane(descriptor, contextPct)
    const status = resolveStepState(entry.obj)
    const isVisibleExecution =
      status === 'running' ||
      status === 'completed' ||
      status === 'failed' ||
      status === 'indeterminate' ||
      status === 'cancelled' ||
      (status === 'waiting_human' && entry.start !== null)
    if (!isVisibleExecution) continue

    const startSec = entry.start !== null ? Math.max((entry.start - t0) / 1000, 0) : cursorSec
    const rawDuration = entry.duration ?? 0
    const endSec = Math.max(startSec + Math.max(rawDuration, MIN_BLOCK_SEC), startSec + MIN_BLOCK_SEC)
    cursorSec = Math.max(cursorSec, endSec)

    const block: TimelineBlock = {
      label: getString(entry.obj, 'name') ?? getString(entry.obj, 'id') ?? 'step',
      ...((getString(entry.obj, 'sourceStepId') ?? getString(entry.obj, 'id'))
        ? { stepId: getString(entry.obj, 'sourceStepId') ?? getString(entry.obj, 'id') }
        : {}),
      startSec,
      endSec,
      status,
    }
    const description = getString(entry.obj, 'description')
    if (description) block.description = description
    const ticksSec = normalizeTicks(entry.obj['ticks'], t0, startSec, endSec)
    if (ticksSec) block.ticksSec = ticksSec
    const errorTicksSec = normalizeTicks(entry.obj['errorTicks'], t0, startSec, endSec)
    if (errorTicksSec) block.errorTicksSec = errorTicksSec
    const blockAgentName = getString(entry.obj, 'agentName')
    if (blockAgentName) block.agentName = blockAgentName
    const blockAttemptId = getString(entry.obj, 'attemptId')
    if (blockAttemptId) block.attemptId = blockAttemptId
    const blockAttemptNumber = getNumber(entry.obj, 'attemptNumber')
    if (blockAttemptNumber !== undefined) block.attemptNumber = blockAttemptNumber
    const blockCaseId = getString(entry.obj, 'caseId')
    if (blockCaseId) block.caseId = blockCaseId
    const blockFailureCode = getString(entry.obj, 'failureCode')
    if (blockFailureCode) block.failureCode = blockFailureCode

    const stepId = getString(entry.obj, 'id')
    const isRequest = stepId === 'request' || block.label === 'request'
    if (descriptor.kind === 'human' && isRequest && !lane.request) {
      lane.request = block
    } else {
      lane.blocks.push(block)
    }
  }

  if (controllerRequest) {
    let lane = lanes.get('engineer')
    if (!lane) {
      lane = {
        id: 'engineer',
        label: 'engineer',
        subtitle: controllerRequest.actorId ?? 'engineer',
        kind: 'human',
        tone: 'amber',
        blocks: [],
      }
      lanes.set('engineer', lane)
    }
    if (!lane.request) {
      const firstStepStart = stepStarts.length > 0 ? Math.min(...stepStarts) : null
      const endSec =
        requestStartMs !== null && firstStepStart !== null && firstStepStart > requestStartMs
          ? Math.max((firstStepStart - t0) / 1000, MIN_BLOCK_SEC)
          : MIN_BLOCK_SEC
      const requestBlock: TimelineBlock = { label: 'request', startSec: 0, endSec, status: 'completed' }
      const requestText = controllerRequest.text ?? controllerRequest.prompt
      if (requestText) requestBlock.description = requestText
      lane.request = requestBlock
    }
    if (controllerRequest.actorId && (!lane.subtitle || lane.subtitle === 'workspace')) {
      lane.subtitle = controllerRequest.actorId
    }
  }

  const rank = (lane: TimelineLane): number => (lane.kind === 'human' ? 0 : lane.kind === 'workspace' ? 1 : 2)
  return [...lanes.values()].sort((a, b) => rank(a) - rank(b))
}

// ---------------------------------------------------------------------------
// Run summary
// ---------------------------------------------------------------------------

export function mapProjectionToRunSummary(item: unknown, metrics?: unknown): RunSummary {
  const snapshot = asObject(item) ?? {}
  const projection = asObject(snapshot['projection']) ?? snapshot
  const relations = asObject(snapshot['relations'])
  const steps = asArray(projection['steps'])
  const realCost = extractRealCost(metrics)

  const durations = steps.map((step) => stepDurationSec(step)).filter((value): value is number => value !== null)
  const durationSec = Math.round(durations.reduce((sum, value) => sum + value, 0))
  const id = getString(snapshot, 'workflowId') ?? getString(projection, 'workflowId') ?? 'unknown'
  const goal =
    getString(projection, 'goal') ??
    getString(projection, 'title') ??
    getString(relations, 'ticket') ??
    getString(snapshot, 'workflowId') ??
    ''

  const stepStates = steps.map(resolveStepState)
  const waitingHuman = stepStates.some((state) => state === 'waiting_human')
  const phases = mapStepsToPhaseSegments(steps, durationSec)

  const controllerRequest = readControllerRequest(snapshot, projection)
  const hasRequestStep = phases.some((segment) => segment.key === 'request' || segment.label === 'request')
  if (controllerRequest && !hasRequestStep) {
    const hasAnyStartedStep = steps.some((step) => getString(asObject(step), 'startedAt') !== undefined)
    const requestSegment: PhaseSegment = {
      key: 'user-request',
      label: 'Demande utilisateur',
      ratio: phases.length > 0 ? Math.min(1 / (phases.length + 1), 0.15) : 1,
      tone: 'amber',
      status: hasAnyStartedStep ? 'done' : 'pending',
    }
    const remaining = 1 - requestSegment.ratio
    const adjusted = phases.map((segment) => ({ ...segment, ratio: segment.ratio * remaining }))
    phases.splice(0, phases.length, requestSegment, ...adjusted)
  }

  const summary: RunSummary = {
    id,
    workflow: getString(projection, 'title') ?? id,
    status: mapWorkflowStateToRunStatus(getString(projection, 'status'), steps),
    currentPhase: currentPhaseName(steps),
    goal,
    costUsd: realCost?.cost ?? getNumber(projection, 'costUsd') ?? getNumber(snapshot, 'costUsd') ?? 0,
    unknownCostCount: realCost?.unknownCostCount ?? 0,
    durationSec,
    tokens: getNumber(projection, 'tokens') ?? 0,
    phases,
  }
  if (waitingHuman) summary.waitingHuman = true
  return summary
}
