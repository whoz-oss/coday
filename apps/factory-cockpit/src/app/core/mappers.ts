import {
  AgentAttempt,
  AgentQuestion,
  AllowedAction,
  AllowedActionType,
  BlockerCode,
  FactoryRun,
  HumanInteraction,
  PhaseDetail,
  PhaseSection,
  PhaseSectionItem,
  PhaseSegment,
  RunEvent,
  RunEventType,
  RunStatus,
  RunSummary,
  SandboxStatus,
  SessionDetail,
  SessionStep,
  TimelineBlock,
  TimelineLane,
  TimelineStepStatus,
  Tone,
  WorkflowBlocker,
} from './models'

/**
 * Mappers from the backend "Workflow Projection v2" JSON (as exposed by
 * `/api/factory/workflows`) to the cockpit UI models.
 *
 * The projection contract (see `factory-service` `WorkflowService.publicSnapshot`
 * and `SessionRunService.persistProjection`) is:
 *
 * ```jsonc
 * {
 *   "workflowId": "wf-1",
 *   "namespaceId": "ns-1",
 *   "revision": 4,
 *   "relations": { "rootWorkflowId": "wf-1", "ticket": "ABC-1" },
 *   "controllerExecution": { "kind": "agentos", "caseId": "…", "agentId": "…" },
 *   "projection": {
 *     "schemaVersion": "2",
 *     "title": "…",
 *     "status": "running",
 *     "steps": [
 *       { "id": "build", "name": "build", "status": "running", "lane": "agent",
 *         "responsibility": { "kind": "agent", "name": "builder" },
 *         "startedAt": "…", "completedAt": "…", "durationMs": 1234 }
 *     ]
 *   }
 * }
 * ```
 *
 * Every function here is pure and defensive: a missing/odd field degrades to a
 * sensible default instead of throwing, so a partially-migrated backend never
 * crashes the cockpit. Lane classification prefers an explicit `lane`, then
 * `responsibility.kind`, then name hints, defaulting to `agent`.
 */

type JsonObject = Record<string, unknown>

function asObject(value: unknown): JsonObject | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as JsonObject) : undefined
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function getString(obj: JsonObject | undefined, key: string): string | undefined {
  const value = obj?.[key]
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function getNumber(obj: JsonObject | undefined, key: string): number | undefined {
  const value = obj?.[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** Coerce an instant (ISO string or epoch ms) to epoch ms, else `null`. */
export function toEpochMs(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** Escape-free (UTC) HH:mm:ss clock label for any ISO instant. */
function formatClock(value?: string): string {
  if (!value) return '--:--:--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--:--:--'
  return date.toISOString().slice(11, 19)
}

// ---------------------------------------------------------------------------
// Step classification (parity with temporal-lanes.mjs)
// ---------------------------------------------------------------------------

const CODE_HINTS = /(oracle|build|test|lint|scan|typecheck|type-check|compile|deterministic|verify|code)/i
const HUMAN_HINTS = /(human|humain|approv|validat|decision|manual|confirm|review|gate|revue)/i

export type ActorKind = 'human' | 'agent' | 'code'

export interface LaneDescriptor {
  id: string
  kind: ActorKind
  label: string
}

/** Resolve the actor kind of a step (explicit lane → responsibility → hints). */
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

/** Classify a step into a concrete lane (human → engineer, code, agent per name). */
export function classifyLane(step: unknown): LaneDescriptor {
  const kind = classifyActorKind(step)
  if (kind === 'human') return { id: 'engineer', kind: 'human', label: 'engineer' }
  if (kind === 'code') return { id: 'code', kind: 'code', label: 'code' }
  const name = actorName(step)
  return { id: `agent:${name}`, kind: 'agent', label: name }
}

export type StepState = TimelineStepStatus

/** Preserve the authoritative Factory step status for truthful rendering. */
export function resolveStepState(step: unknown): StepState {
  const status = getString(asObject(step), 'status') ?? 'pending'
  return (
    ['pending', 'ready', 'running', 'waiting_human', 'completed', 'failed', 'indeterminate', 'cancelled'] as const
  ).includes(status as TimelineStepStatus)
    ? (status as TimelineStepStatus)
    : 'pending'
}

/** Duration of a step in seconds, or `null` when the projection does not provide it. */
export function stepDurationSec(step: unknown): number | null {
  const obj = asObject(step)
  const durationMs = getNumber(obj, 'durationMs')
  if (durationMs !== undefined) return durationMs / 1000
  const start = toEpochMs(obj?.['startedAt'])
  const end = toEpochMs(obj?.['completedAt'] ?? obj?.['endedAt'])
  if (start !== null && end !== null) return Math.max(end - start, 0) / 1000
  return null
}

function toneForActor(kind: ActorKind): Tone {
  if (kind === 'human') return 'amber'
  if (kind === 'code') return 'cyan'
  return 'violet'
}

// ---------------------------------------------------------------------------
// RunStatus / phases
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
 * Lifecycle states (`active`, `existing`, `removed`) are derived from the steps.
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

function phaseSegmentStatus(step: unknown): PhaseSegment['status'] {
  const state = resolveStepState(step)
  if (state === 'waiting_human') return 'waiting_human'
  if (state === 'running') return 'running'
  if (state === 'pending' || state === 'ready') return 'pending'
  if (state === 'failed') return 'failed'
  if (state === 'cancelled') return 'cancelled'
  if (state === 'indeterminate') return 'indeterminate'
  return 'done' // completed
}

function phaseTone(step: unknown): Tone {
  return ['failed', 'indeterminate'].includes(resolveStepState(step)) ? 'red' : toneForActor(classifyActorKind(step))
}

/** Derive the phase-bar segments from the projection steps. */
export function mapStepsToPhaseSegments(steps: unknown[], totalDurationSec = 0): PhaseSegment[] {
  const list = Array.isArray(steps) ? steps : []
  if (list.length === 0) return []
  const durations = list.map((step) => stepDurationSec(step) ?? 0)
  const summed = durations.reduce((sum, duration) => sum + duration, 0)
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
    // Carry the readable name only when it differs from the id (avoids redundancy).
    if (name && name !== id) segment.label = name
    return segment
  })
}

function currentPhaseName(steps: unknown[]): string | undefined {
  const active = steps.find((step) => ['running', 'waiting_human', 'ready'].includes(resolveStepState(step)))
  if (active) return getString(asObject(active), 'name') ?? getString(asObject(active), 'id')
  const last = steps[steps.length - 1]
  return last ? (getString(asObject(last), 'name') ?? getString(asObject(last), 'id')) : undefined
}

// ---------------------------------------------------------------------------
// Real cost (metrics.realCost)
// ---------------------------------------------------------------------------

/**
 * Additive real-cost block exposed by `factory-service` under
 * `metrics.realCost` (see `WorkflowService.metrics`). Every field is optional
 * here because the cockpit must survive a partially-migrated backend.
 */
export interface RealCost {
  cost?: number
  unknownCostCount?: number
  liveTokens?: number
  paused?: boolean
  active?: boolean
  runCostThreshold?: number | null
}

function getBoolean(obj: JsonObject | undefined, key: string): boolean | undefined {
  const value = obj?.[key]
  return typeof value === 'boolean' ? value : undefined
}

/**
 * Defensively extract the `realCost` block from a metrics payload.
 *
 * The backend nests it under `metrics.realCost`; for robustness we also accept
 * the block flattened directly on `metrics` (or the metrics payload itself
 * already being the block). Returns `undefined` when no usable cost field is
 * present so callers can fall back to the projection/snapshot cost.
 */
export function extractRealCost(metrics: unknown): RealCost | undefined {
  const metricsObj = asObject(metrics)
  if (!metricsObj) return undefined
  const source = asObject(metricsObj['realCost']) ?? metricsObj

  const cost = getNumber(source, 'cost')
  const unknownCostCount = getNumber(source, 'unknownCostCount')
  const liveTokens = getNumber(source, 'liveTokens')
  if (cost === undefined && unknownCostCount === undefined && liveTokens === undefined) return undefined

  const thresholdRaw = source['runCostThreshold']
  const runCostThreshold = thresholdRaw === null ? null : (getNumber(source, 'runCostThreshold') ?? null)

  return {
    cost,
    unknownCostCount,
    liveTokens,
    paused: getBoolean(source, 'paused'),
    active: getBoolean(source, 'active'),
    runCostThreshold,
  }
}

// ---------------------------------------------------------------------------
// RunSummary
// ---------------------------------------------------------------------------

/** Map a `/api/factory/workflows` snapshot to a {@link RunSummary}. */
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

  // Derive waiting_human from steps — same logic as mapProjectionToSessionDetail.
  // This stays separate from `status` (which remains 'running') so the global
  // sandbox badge (SandboxStatus 'working') is not altered.
  const stepStates = steps.map(resolveStepState)
  const waitingHuman = stepStates.some((state) => state === 'waiting_human')

  const phases = mapStepsToPhaseSegments(steps, durationSec)

  // Inject a synthetic "Demande utilisateur" segment from the persisted
  // controllerRequest when no step with id/name 'request' already appears in
  // the phases (avoids duplication). The status is derived from real data only:
  // 'done' when at least one step has started (the request is implicitly
  // complete), 'pending' otherwise. No success is fabricated.
  const controllerRequest = readControllerRequest(snapshot, projection)
  const hasRequestStep = phases.some((segment) => segment.key === 'request' || segment.label === 'request')
  if (controllerRequest && !hasRequestStep) {
    const hasAnyStartedStep = steps.some((step) => getString(asObject(step), 'startedAt') !== undefined)
    const requestSegment: PhaseSegment = {
      key: 'user-request',
      label: 'Demande utilisateur',
      // Ratio: give the request segment a minimal visual presence (same as
      // a 1-step even split at most, but capped to avoid dominating the bar).
      ratio: phases.length > 0 ? Math.min(1 / (phases.length + 1), 0.15) : 1,
      tone: 'amber',
      status: hasAnyStartedStep ? 'done' : 'pending',
    }
    // Adjust existing segment ratios to preserve proportions after insertion.
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
    // Real cost wins; otherwise fall back to the projection/snapshot cost, else 0.
    costUsd: realCost?.cost ?? getNumber(projection, 'costUsd') ?? getNumber(snapshot, 'costUsd') ?? 0,
    // `unknownCostCount` is preserved verbatim (never folded into cost as 0).
    unknownCostCount: realCost?.unknownCostCount ?? 0,
    durationSec,
    tokens: getNumber(projection, 'tokens') ?? 0,
    phases,
  }
  if (waitingHuman) summary.waitingHuman = true
  return summary
}

// ---------------------------------------------------------------------------
// Timeline lanes
// ---------------------------------------------------------------------------

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
  if (status === 'failed' || status === 'indeterminate' || status === 'running' || status === 'waiting_human') {
    return status
  }
  return 'pending'
}

/**
 * Replace agent steps with their durable execution attempts for the timeline.
 * Each retry becomes a separate, chronologically ordered block; non-agent
 * steps and agent steps without attempt history remain unchanged.
 */
function timelineSteps(steps: unknown[], attempts: AgentAttempt[]): unknown[] {
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
            ? `${getString(obj, 'name') ?? stepId} · attempt ${attempt.attemptNumber || '?'}`
            : (getString(obj, 'name') ?? stepId),
        agentName: attempt.agentName || getString(obj, 'agentName'),
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

/**
 * Normalized engineer request carried by a snapshot (backend
 * `controllerRequest`, see `WorkflowService.publicSnapshot`).
 */
export interface ControllerRequest {
  text?: string
  prompt?: string
  namespaceId?: string
  observedAt?: string
  actorId?: string
  source?: string
}

/**
 * Read the persisted engineer request defensively.
 *
 * The backend exposes it at `snapshot.controllerRequest`; legacy payloads may
 * carry it on the projection/instance or as a raw string, so both are accepted.
 * Returns `undefined` when nothing usable is present.
 */
function readControllerRequest(snapshot: JsonObject, projection: JsonObject): ControllerRequest | undefined {
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

function stepBlockStatus(step: unknown): TimelineBlock['status'] {
  return resolveStepState(step)
}

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
 *
 * Blocks are positioned in real time (relative seconds from the workflow
 * start). Steps without timing are queued sequentially so the strip never
 * overlaps. Human steps whose id/name is `request` are surfaced in the
 * dedicated `request` column, and the persisted `controllerRequest` (the
 * initial human command) is surfaced as the engineer lane's `request` block
 * when no `request` step already provides one.
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
  // The persisted request anchors the timeline start when present (it is the
  // first human activity), so the engineer block always starts at 0s.
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
  const ensureLane = (descriptor: LaneDescriptor, contextPct?: number): TimelineLane => {
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
    // Lane topology describes the workflow plan, independently from whether an
    // execution has started. Pending/ready steps therefore create an empty lane.
    const descriptor = classifyLane(entry.obj)
    const contextPct = getNumber(entry.obj, 'contextPct')
    const lane = ensureLane(descriptor, contextPct)
    const status = stepBlockStatus(entry.obj)
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

    const stepId = getString(entry.obj, 'id')
    const isRequest = stepId === 'request' || block.label === 'request'
    if (descriptor.kind === 'human' && isRequest && !lane.request) {
      lane.request = block
    } else {
      lane.blocks.push(block)
    }
  }

  // Persisted engineer request: the `controllerRequest` is the authoritative
  // initial human command. Surface it as the engineer lane's `request` block
  // when the steps loop did not already derive one from a `request` step (in
  // which case we must not duplicate the lane or the block).
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
      const requestBlock: TimelineBlock = {
        label: 'request',
        startSec: 0,
        endSec,
        status: 'completed',
      }
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
// Session detail
// ---------------------------------------------------------------------------

function firstStartedAt(steps: unknown[]): string | undefined {
  for (const step of steps) {
    const startedAt = getString(asObject(step), 'startedAt')
    if (startedAt) return startedAt
  }
  return undefined
}

function sumFacts(items: unknown[], key: string): number {
  let total = 0
  for (const item of items) {
    const facts = asObject(asObject(item)?.['facts'])
    const value = getNumber(facts, key)
    if (value !== undefined) total += value
  }
  return total
}

function eventTypeFor(kind: string): RunEventType {
  const normalized = kind.toLowerCase()
  if (normalized.includes('tool')) return 'tool_call'
  if (normalized.includes('think')) return 'thinking'
  if (normalized.includes('human') || normalized.includes('decision') || normalized.includes('agent')) {
    return 'agent_message'
  }
  if (normalized.includes('phase')) return 'phase_start'
  return 'log'
}

function mapEvidenceToEvents(items: unknown[], steps: unknown[]): RunEvent[] {
  const events: RunEvent[] = []
  for (const item of items) {
    const obj = asObject(item) ?? {}
    const facts = asObject(obj['facts']) ?? {}
    const kind = getString(obj, 'kind') ?? 'log'
    const event: RunEvent = {
      time: formatClock(getString(obj, 'createdAt')),
      type: eventTypeFor(kind),
      text: getString(facts, 'message') ?? getString(facts, 'text') ?? getString(obj, 'outcome') ?? kind,
    }
    const tool = getString(facts, 'tool')
    if (tool) event.tool = tool
    const durationMs = getNumber(facts, 'durationMs')
    if (durationMs !== undefined) event.durationSec = durationMs / 1000
    events.push(event)
  }

  if (events.length === 0) {
    for (const step of steps) {
      const obj = asObject(step) ?? {}
      const startedAt = getString(obj, 'startedAt')
      if (!startedAt) continue
      events.push({
        time: formatClock(startedAt),
        type: 'phase_start',
        text: getString(obj, 'name') ?? getString(obj, 'id') ?? 'step',
      })
    }
  }
  return events
}

function mapStepsToSessionSteps(steps: unknown[]): SessionStep[] {
  return steps.map((step, index) => {
    const obj = asObject(step) ?? {}
    const state = resolveStepState(obj)
    const result: SessionStep = {
      key: getString(obj, 'id') ?? getString(obj, 'name') ?? `step-${index + 1}`,
      tone: phaseTone(obj),
      status:
        state === 'waiting_human'
          ? 'waiting_human'
          : state === 'running'
            ? 'running'
            : state === 'pending' || state === 'ready'
              ? 'pending'
              : 'done',
    }
    const duration = stepDurationSec(obj)
    if (duration !== null) result.durationSec = Math.round(duration)
    return result
  })
}

// ---------------------------------------------------------------------------
// Human interactions (read-only)
// ---------------------------------------------------------------------------

function asInteractionArray(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asObject(payload)
  if (!obj) return []
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}

function extractActions(value: unknown): Array<{ id: string; label: string }> | undefined {
  if (!Array.isArray(value)) return undefined
  const actions: Array<{ id: string; label: string }> = []
  value.forEach((entry, index) => {
    if (typeof entry === 'string' && entry.length > 0) {
      actions.push({ id: entry, label: entry })
      return
    }
    const obj = asObject(entry)
    if (!obj) return
    const id = getString(obj, 'id') ?? getString(obj, 'actionId') ?? String(index + 1)
    const label = getString(obj, 'label') ?? getString(obj, 'name') ?? getString(obj, 'title') ?? id
    actions.push({ id, label })
  })
  return actions.length > 0 ? actions : undefined
}

/**
 * Defensively extract the human interactions carried by a `getInteractions`
 * payload. Accepts a bare array, an `{ items: [...] }` wrapper or a
 * `{ data: [...] }` wrapper; anything else degrades to `[]`. Each record is
 * mapped to a {@link HumanInteraction} with graceful defaults so a
 * partially-migrated backend never throws.
 */
export function extractInteractions(payload: unknown): HumanInteraction[] {
  return asInteractionArray(payload).map((item, index) => {
    const obj = asObject(item) ?? {}
    const payloadObj = asObject(obj['payload']) ?? {}
    const interaction: HumanInteraction = {
      interactionId: getString(obj, 'interactionId') ?? getString(obj, 'id') ?? `interaction-${index + 1}`,
      stepId: getString(obj, 'stepId') ?? getString(payloadObj, 'stepId') ?? '',
      interactionType: getString(obj, 'interactionType') ?? getString(obj, 'type') ?? 'unknown',
      status: getString(obj, 'status') ?? 'unknown',
    }
    const workflowId = getString(obj, 'workflowId')
    if (workflowId) interaction.workflowId = workflowId
    const revision = getNumber(obj, 'revision')
    if (revision !== undefined) interaction.revision = revision
    const prompt = getString(payloadObj, 'prompt') ?? getString(payloadObj, 'question') ?? getString(obj, 'prompt')
    if (prompt) interaction.prompt = prompt
    const questionType =
      getString(obj, 'questionType') ?? getString(payloadObj, 'questionType') ?? getString(payloadObj, 'type')
    if (questionType) interaction.questionType = questionType
    const rawOptions = payloadObj['options'] ?? obj['options']
    if (Array.isArray(rawOptions))
      interaction.options = rawOptions.filter((option): option is string => typeof option === 'string')
    const actions = extractActions(payloadObj['actions'] ?? obj['actions'])
    if (actions) interaction.actions = actions
    const recipient = getString(payloadObj, 'recipient') ?? getString(obj, 'recipient')
    if (recipient) interaction.recipient = recipient
    const recipientRole = getString(obj, 'recipientRole') ?? getString(payloadObj, 'recipientRole')
    if (recipientRole) interaction.recipientRole = recipientRole
    const namespaceId = getString(obj, 'namespaceId') ?? getString(payloadObj, 'namespaceId')
    if (namespaceId) interaction.namespaceId = namespaceId
    const createdAt = getString(obj, 'createdAt') ?? getString(payloadObj, 'createdAt')
    if (createdAt) interaction.createdAt = createdAt
    return interaction
  })
}

/** Surface each human interaction as a read-only {@link RunEvent}. */
export function mapInteractionsToEvents(interactions: HumanInteraction[]): RunEvent[] {
  return interactions.map((interaction) => ({
    time: formatClock(interaction.createdAt),
    type: 'agent_message',
    text: `[Human Gate - ${interaction.interactionType}] ${interaction.prompt ?? interaction.status}`,
  }))
}

// ---------------------------------------------------------------------------
// Real agent attempts (read-only)
// ---------------------------------------------------------------------------

function asAttemptArray(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asObject(payload)
  if (!obj) return []
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}

/**
 * Defensively extract the real agent execution attempts carried by a
 * `getAttempts` payload (backend `DurableAgentAttemptDto`). Accepts a bare
 * array or an `{ items: [...] }` / `{ data: [...] }` wrapper; anything else
 * degrades to `[]`. Each record is mapped with graceful defaults so a
 * partially-migrated backend never throws.
 */
export function extractAttempts(payload: unknown): AgentAttempt[] {
  return asAttemptArray(payload).map((item, index) => {
    const obj = asObject(item) ?? {}
    const attempt: AgentAttempt = {
      attemptId: getString(obj, 'attemptId') ?? getString(obj, 'id') ?? `attempt-${index + 1}`,
      stepId: getString(obj, 'stepId') ?? '',
      attemptNumber: getNumber(obj, 'attemptNumber') ?? 0,
      agentName: getString(obj, 'agentName') ?? '',
      status: getString(obj, 'status') ?? 'unknown',
      caseId: getString(obj, 'caseId') ?? '',
    }
    const failureCode = getString(obj, 'failureCode')
    if (failureCode) attempt.failureCode = failureCode
    const resultEvidenceId = getString(obj, 'resultEvidenceId')
    if (resultEvidenceId) attempt.resultEvidenceId = resultEvidenceId
    const revision = getNumber(obj, 'revision')
    if (revision !== undefined) attempt.revision = revision
    const createdAt = getString(obj, 'createdAt')
    if (createdAt) attempt.createdAt = createdAt
    const startedAt = getString(obj, 'startedAt')
    if (startedAt) attempt.startedAt = startedAt
    const completedAt = getString(obj, 'completedAt')
    if (completedAt) attempt.completedAt = completedAt
    return attempt
  })
}

// ---------------------------------------------------------------------------
// Governed actions & blockers (backend authority)
// ---------------------------------------------------------------------------

/** Extract a list from a bare array, a `{ [key]: [...] }` block or `items`/`data` wrappers. */
function asList(payload: unknown, key: string): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asObject(payload)
  if (!obj) return []
  const direct = obj[key]
  if (Array.isArray(direct)) return direct
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}

/**
 * Defensively extract `allowedActions` from a `getActions` payload.
 *
 * Accepts the normalized `{ allowedActions: [...] }` block, a `{ items }` /
 * `{ data }` wrapper or a bare array; anything else degrades to `[]`. Entries
 * without a usable `type` are dropped (an action the cockpit cannot name must
 * never be rendered), while every identity field is copied verbatim — never
 * invented.
 */
export function extractAllowedActions(payload: unknown): AllowedAction[] {
  const actions: AllowedAction[] = []
  for (const item of asList(payload, 'allowedActions')) {
    const obj = asObject(item)
    if (!obj) continue
    const type = getString(obj, 'type')
    if (!type) continue
    const action: AllowedAction = { type: type as AllowedActionType }
    const label = getString(obj, 'label')
    if (label) action.label = label
    const interactionId = getString(obj, 'interactionId')
    if (interactionId) action.interactionId = interactionId
    const stepId = getString(obj, 'stepId')
    if (stepId) action.stepId = stepId
    const attemptId = getString(obj, 'attemptId')
    if (attemptId) action.attemptId = attemptId
    const caseId = getString(obj, 'caseId')
    if (caseId) action.caseId = caseId
    const questionEventId = getString(obj, 'questionEventId')
    if (questionEventId) action.questionEventId = questionEventId
    const expectedRevision = getNumber(obj, 'expectedRevision')
    if (expectedRevision !== undefined) action.expectedRevision = expectedRevision
    actions.push(action)
  }
  return actions
}

/**
 * Defensively extract `blockers` from a `getActions` payload. Accepts the
 * normalized `{ blockers: [...] }` block, `items`/`data` wrappers or a bare
 * array; anything else degrades to `[]`. The backend field is `message`; it is
 * surfaced as {@link WorkflowBlocker.label} too so the UI always has a label.
 */
export function extractBlockers(payload: unknown): WorkflowBlocker[] {
  const blockers: WorkflowBlocker[] = []
  for (const item of asList(payload, 'blockers')) {
    const obj = asObject(item)
    if (!obj) continue
    const code = getString(obj, 'code') ?? 'UNKNOWN_RUNTIME'
    const message = getString(obj, 'message')
    const label = getString(obj, 'label') ?? message ?? getString(obj, 'details') ?? code
    const blocker: WorkflowBlocker = { code: code as BlockerCode, label }
    const stepId = getString(obj, 'stepId')
    if (stepId) blocker.stepId = stepId
    if (message) blocker.message = message
    const details = getString(obj, 'details')
    if (details) blocker.details = details
    blockers.push(blocker)
  }
  return blockers
}

function pickCurrentAttempt(stepAttempts: AgentAttempt[]): AgentAttempt | undefined {
  if (stepAttempts.length === 0) return undefined
  return stepAttempts.reduce((latest, attempt) => (attempt.attemptNumber >= latest.attemptNumber ? attempt : latest))
}

/** Map the real human interactions of the active step to section items. */
function interactionToItems(interactions: HumanInteraction[]): PhaseSectionItem[] {
  return interactions.map((interaction) => {
    const item: PhaseSectionItem = { title: interaction.interactionType, status: interaction.status }
    if (interaction.prompt) item.subtitle = interaction.prompt
    if (interaction.actions) item.actions = interaction.actions
    return item
  })
}

/** Map the real evidence records of the active step to output section items. */
function evidenceToItems(evidenceItems: unknown[]): PhaseSectionItem[] {
  return evidenceItems.map((entry, index) => {
    const obj = asObject(entry) ?? {}
    const facts = asObject(obj['facts']) ?? {}
    const kind = getString(obj, 'kind') ?? getString(obj, 'evidenceId') ?? `sortie-${index + 1}`
    const item: PhaseSectionItem = { title: kind }
    const outcome = getString(obj, 'outcome')
    if (outcome) item.status = outcome
    const message = getString(facts, 'message') ?? getString(facts, 'text') ?? getString(facts, 'summary') ?? outcome
    if (message) item.subtitle = message
    return item
  })
}

function buildPhaseDetail(
  steps: unknown[],
  fallbackStatus: RunStatus,
  interactions: HumanInteraction[] = [],
  attempts: AgentAttempt[] = [],
  evidenceItems: unknown[] = []
): PhaseDetail {
  const active =
    steps.find((step) => ['running', 'waiting_human', 'ready'].includes(resolveStepState(step))) ??
    steps[steps.length - 1]
  const obj = asObject(active) ?? {}
  const responsibility = asObject(obj['responsibility'])
  const duration = stepDurationSec(obj) ?? 0
  const description = getString(obj, 'description')
  const stepId = getString(obj, 'id') ?? getString(obj, 'key')
  const owner = getString(responsibility, 'name') ?? actorName(obj)
  const kind = getString(responsibility, 'kind') ?? classifyActorKind(obj)

  const stepAttempts = stepId ? attempts.filter((attempt) => attempt.stepId === stepId) : []
  const currentAttempt = pickCurrentAttempt(stepAttempts)

  const detail: PhaseDetail = {
    name: getString(obj, 'name') ?? getString(obj, 'id') ?? 'phase',
    status: getString(obj, 'status') ? mapWorkflowStateToRunStatus(getString(obj, 'status'), [obj]) : fallbackStatus,
    stepStatus: resolveStepState(obj),
    durationSec: Math.round(duration),
    owner,
    kind,
    // Real attempts are surfaced when available; a neutral `1/1` fallback is
    // used when the backend exposes none.
    attempt: '1/1',
    sections: [],
  }

  if (stepId) detail.stepId = stepId

  if (stepAttempts.length > 0 && currentAttempt) {
    detail.attempt = `${currentAttempt.attemptNumber}/${stepAttempts.length}`
    detail.currentAttemptNumber = currentAttempt.attemptNumber
    detail.totalAttempts = stepAttempts.length
    detail.attempts = stepAttempts
    detail.agentName = currentAttempt.agentName
    detail.attemptStatus = currentAttempt.status
    detail.caseId = currentAttempt.caseId
    if (currentAttempt.failureCode) detail.failureCode = currentAttempt.failureCode
  }

  // ── Gates: the real interactions attached to the active step. ──────────────
  const stepInteractions = stepId ? interactions.filter((interaction) => interaction.stepId === stepId) : interactions
  const gates: PhaseSection = {
    label: 'Gates',
    count: stepInteractions.length,
    items: interactionToItems(stepInteractions),
  }
  if (stepInteractions.length === 0) gates.body = "Aucune gate d'interaction pour cette phase."

  // ── Sorties: the real evidence of the active step (or of the current
  // attempt's `resultEvidenceId`). ────────────────────────────────────────────
  const stepEvidence = evidenceItems.filter((entry) => {
    const evidenceObj = asObject(entry) ?? {}
    const evidenceStepId = getString(evidenceObj, 'stepId')
    const evidenceId = getString(evidenceObj, 'evidenceId') ?? getString(evidenceObj, 'id')
    const matchesStep = stepId !== undefined && evidenceStepId === stepId
    const matchesAttempt =
      currentAttempt?.resultEvidenceId !== undefined && evidenceId === currentAttempt.resultEvidenceId
    return matchesStep || matchesAttempt
  })
  const outputs: PhaseSection = {
    label: 'Sorties',
    count: stepEvidence.length,
    items: evidenceToItems(stepEvidence),
  }
  if (stepEvidence.length === 0) outputs.body = 'Aucune sortie enregistrée pour cette phase.'

  // ── Configuration de l'agent: only the real fields exposed by the backend. ─
  const agentItems: PhaseSectionItem[] = []
  if (detail.agentName ?? owner) agentItems.push({ title: 'Agent', subtitle: detail.agentName ?? owner })
  if (owner) agentItems.push({ title: 'Rôle', subtitle: `${owner} (${kind})` })
  if (detail.caseId) agentItems.push({ title: 'Case', subtitle: detail.caseId })
  if (detail.totalAttempts) agentItems.push({ title: 'Tentatives', subtitle: detail.attempt })
  const agentConfig: PhaseSection = { label: "Configuration de l'agent", items: agentItems }
  if (agentItems.length === 0) agentConfig.body = 'Information agent non disponible'

  detail.sections = [gates, outputs, agentConfig]
  if (description) detail.sections.push({ label: 'Description', body: description })
  // No backend field exists today for the compiled prompts or the resolved LLM
  // model. They are surfaced explicitly as unavailable (`notAvailable`) rather
  // than as a misleading `count: 0` that would imply an empty (but known) list.
  detail.sections.push(
    { label: 'Prompts compilés', notAvailable: true, body: 'Non disponible (nécessite exposition backend)' },
    { label: 'Modèle LLM résolu', notAvailable: true, body: 'Non disponible (nécessite exposition backend)' }
  )

  return detail
}

/** Resolve the workflow id carried by a list/detail snapshot. */
export function workflowIdOf(snapshot: unknown): string | undefined {
  const obj = asObject(snapshot)
  const projection = asObject(obj?.['projection'])
  return getString(obj, 'workflowId') ?? getString(projection, 'workflowId')
}

/** Resolve the namespace id carried by a list/detail snapshot. */
export function namespaceOf(snapshot: unknown): string | undefined {
  const obj = asObject(snapshot)
  const instance = asObject(obj?.['instance'])
  return getString(obj, 'namespaceId') ?? getString(instance, 'namespaceId')
}

/**
 * Resolve the AgentOS controller case id from a workflow snapshot.
 *
 * This is the STARTING case created by the Factory controller execution
 * (`snapshot.controllerExecution.caseId`). It is distinct from attempt
 * case ids (which change on each retry) and question case ids.
 * Returns `undefined` when the snapshot does not expose a controller case.
 */
export function controllerCaseIdOf(snapshot: unknown): string | undefined {
  const obj = asObject(snapshot)
  const controller = asObject(obj?.['controllerExecution'])
  return getString(controller, 'caseId')
}

/**
 * Map a workflow snapshot (+ optional timing / evidence / metrics payloads) to a
 * complete {@link SessionDetail}. Any missing enrichment simply yields defaults.
 */
export function mapProjectionToSessionDetail(
  workflow: unknown,
  timing?: unknown,
  evidence?: unknown,
  metrics?: unknown,
  interactions?: unknown,
  attempts?: unknown,
  actions?: unknown,
  agentQuestions?: AgentQuestion[]
): SessionDetail {
  const snapshot = asObject(workflow) ?? {}
  const projection = asObject(snapshot['projection']) ?? snapshot
  const steps = asArray(projection['steps'])
  const relations =
    asObject(snapshot['relations']) ?? asObject(asObject(snapshot['instance'])?.['relations']) ?? undefined
  const controller = asObject(snapshot['controllerExecution'])
  const timingObj = asObject(timing)
  const metricsObj = asObject(metrics)
  const realCost = extractRealCost(metricsObj)
  const evidenceItems = asArray(asObject(evidence)?.['items'])
  const mappedInteractions = extractInteractions(interactions)
  const mappedAttempts = extractAttempts(attempts)
  const mappedAllowedActions = extractAllowedActions(actions)
  const mappedBlockers = extractBlockers(actions)

  // The active attempt the cockpit may cancel is resolved ONLY from real state:
  // a backend `cancel_attempt` allowed action (authoritative), else a running
  // attempt carrying a real `attemptId`. Never fabricate an id or revision.
  const cancelAttemptAction = mappedAllowedActions.find(
    (action) => action.type === 'cancel_attempt' && action.attemptId !== undefined
  )
  const runningAttempt = mappedAttempts.find((attempt) => attempt.status === 'running')
  const activeAttemptId = cancelAttemptAction?.attemptId ?? runningAttempt?.attemptId
  const activeAttemptRevision = cancelAttemptAction?.expectedRevision ?? runningAttempt?.revision

  const id = getString(snapshot, 'workflowId') ?? getString(projection, 'workflowId') ?? 'unknown'
  const controllerCaseId = controllerCaseIdOf(snapshot)
  const status = mapWorkflowStateToRunStatus(getString(projection, 'status'), steps)
  const stepStates = steps.map(resolveStepState)
  const waitingHuman =
    stepStates.includes('waiting_human') || mappedAttempts.some((attempt) => attempt.status === 'waiting_human')
  const activelyRunning = !waitingHuman && stepStates.includes('running')
  const executionState: TimelineStepStatus = waitingHuman
    ? 'waiting_human'
    : activelyRunning
      ? 'running'
      : status === 'succeeded'
        ? 'completed'
        : status === 'failed'
          ? 'failed'
          : 'pending'
  const lanes = mapProjectionToLanes(snapshot, timing, mappedAttempts)
  const laneEnd = lanes.reduce((max, lane) => {
    const requestEnd = lane.request?.endSec ?? 0
    const blocksEnd = lane.blocks.reduce((end, block) => Math.max(end, block.endSec), 0)
    return Math.max(max, requestEnd, blocksEnd)
  }, 0)

  const durations = steps.map((step) => stepDurationSec(step)).filter((value): value is number => value !== null)
  const summedDuration = durations.reduce((sum, value) => sum + value, 0)
  const tokensRead = sumFacts(evidenceItems, 'tokensRead')
  const tokensWritten = sumFacts(evidenceItems, 'tokensWritten')
  const liveTokens = realCost?.liveTokens && realCost.liveTokens > 0 ? realCost.liveTokens : undefined
  const tokens =
    getNumber(projection, 'tokens') ?? liveTokens ?? getNumber(metricsObj, 'tokens') ?? tokensRead + tokensWritten

  const result: SessionDetail = {
    id,
    sandbox:
      getString(relations, 'ticket') ?? getString(controller, 'caseId') ?? getString(snapshot, 'namespaceId') ?? id,
    goal: getString(projection, 'goal') ?? getString(projection, 'title') ?? '',
    status,
    executionState,
    activelyRunning,
    startedAt:
      getString(timingObj, 'startedAt') ??
      getString(projection, 'startedAt') ??
      firstStartedAt(steps) ??
      new Date().toISOString(),
    workflow: getString(projection, 'title') ?? id,
    // Real cost wins; otherwise fall back to projection/metrics/snapshot cost, else 0.
    costUsd:
      realCost?.cost ??
      getNumber(projection, 'costUsd') ??
      getNumber(metricsObj, 'costUsd') ??
      getNumber(snapshot, 'costUsd') ??
      0,
    // `unknownCostCount` is preserved verbatim (never folded into cost as 0).
    unknownCostCount: realCost?.unknownCostCount ?? 0,
    durationSec: Math.max(Math.round(summedDuration), Math.round(laneEnd)),
    tokens,
    tokensRead,
    tokensWritten,
    steps: mapStepsToSessionSteps(steps),
    lanes,
    nowSec: Math.round(laneEnd),
    events: [...mapEvidenceToEvents(evidenceItems, steps), ...mapInteractionsToEvents(mappedInteractions)],
    phase: buildPhaseDetail(steps, status, mappedInteractions, mappedAttempts, evidenceItems),
    interactions: mappedInteractions,
    attempts: mappedAttempts,
    allowedActions: mappedAllowedActions,
    blockers: mappedBlockers,
    agentQuestions: agentQuestions ?? [],
    ...(activeAttemptId !== undefined ? { activeAttemptId } : {}),
    ...(activeAttemptRevision !== undefined ? { activeAttemptRevision } : {}),
    ...(controllerCaseId !== undefined ? { controllerCaseId } : {}),
  }
  return result
}

// ---------------------------------------------------------------------------
// FactoryRun (workstream → runs projections)
// ---------------------------------------------------------------------------

const RUN_WORKING_STATES = new Set(['running', 'active', 'waiting_human'])
const RUN_IDLE_STATES = new Set(['idle', 'ready', 'pending', 'queued'])

/** Map a real workflow state onto the cockpit run lifecycle status. */
export function deriveRunStatus(state: string | undefined, runStatus: RunStatus): SandboxStatus {
  const normalized = (state ?? '').toLowerCase()
  if (RUN_WORKING_STATES.has(normalized)) return 'working'
  if (RUN_IDLE_STATES.has(normalized)) return 'idle'
  return runStatus === 'running' ? 'working' : 'idle'
}

function isValidIsoDate(value: string): boolean {
  if (!value) return false
  return Number.isFinite(Date.parse(value))
}

/**
 * Map one workflow snapshot onto a displayable {@link FactoryRun}.
 *
 * The run `id` (workflow id) is the unique identity used for every action. The
 * `namespaceId` is extracted with {@link namespaceOf} and carried verbatim so
 * the store can group runs strictly by namespace. Every other field comes from
 * the snapshot/relations; nothing is fabricated.
 */
export function mapProjectionToFactoryRun(snapshot: unknown, forcedStatus?: SandboxStatus): FactoryRun {
  const run = mapProjectionToRunSummary(snapshot)
  const obj = asObject(snapshot) ?? {}
  const projection = asObject(obj['projection']) ?? obj
  const relations = asObject(obj['relations']) ?? asObject(asObject(obj['instance'])?.['relations'])
  const namespaceId = namespaceOf(snapshot)
  const ticket = getString(relations, 'ticket')
  const branch = getString(relations, 'branch') ?? ticket
  const workflowType = getString(projection, 'workflowType')
  const title = getString(projection, 'title')
  const goal = getString(projection, 'goal')
  const state = getString(projection, 'status')
  const name = title ?? (run.id !== 'unknown' ? run.id : (ticket ?? goal ?? 'workflow'))

  const factoryRun: FactoryRun = {
    id: run.id,
    title: name,
    project: namespaceId ?? ticket ?? 'coday',
    status: forcedStatus ?? deriveRunStatus(state, run.status),
    costUsd: run.costUsd,
    durationSec: run.durationSec,
    tokens: run.tokens,
    phases: run.phases,
    run,
  }
  if (namespaceId) factoryRun.namespaceId = namespaceId
  if (workflowType) factoryRun.workflowType = workflowType
  if (ticket) factoryRun.ticket = ticket
  if (branch) factoryRun.branch = branch
  const controllerCaseId = controllerCaseIdOf(snapshot)
  if (controllerCaseId) factoryRun.controllerCaseId = controllerCaseId
  const rawCreatedAt = getString(obj, 'createdAt')
  if (rawCreatedAt && isValidIsoDate(rawCreatedAt)) factoryRun.createdAt = rawCreatedAt
  return factoryRun
}
