import {
  PhaseDetail,
  PhaseSegment,
  RunEvent,
  RunEventType,
  RunStatus,
  RunSummary,
  SessionDetail,
  SessionStep,
  TimelineBlock,
  TimelineLane,
  Tone,
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
 * crashes the cockpit. The lane classification mirrors the vanilla
 * `factory/dashboard/js/components/temporal-lanes.mjs` logic (explicit `lane`
 * first, then `responsibility.kind`, then name hints, defaulting to `agent`).
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

export type StepState = 'completed' | 'active' | 'pending' | 'failed'

/** Map a projection step status to the four visual states. */
export function resolveStepState(step: unknown): StepState {
  const obj = asObject(step)
  const status = getString(obj, 'status') ?? 'pending'
  switch (status) {
    case 'completed':
    case 'cancelled':
      return 'completed'
    case 'failed':
      return 'failed'
    case 'running':
    case 'waiting_human':
    case 'ready':
      return 'active'
    default:
      return 'pending'
  }
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
  if (states.includes('failed')) return 'failed'
  if (states.includes('active')) return 'running'
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
    case 'ready':
    case 'waiting_human':
    case 'active':
    case 'existing':
      return 'running'
    case 'completed':
    case 'succeeded':
      return 'succeeded'
    case 'failed':
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
  if (state === 'active') return 'running'
  if (state === 'pending') return 'pending'
  return 'done'
}

function phaseTone(step: unknown): Tone {
  return resolveStepState(step) === 'failed' ? 'red' : toneForActor(classifyActorKind(step))
}

/** Derive the phase-bar segments from the projection steps. */
export function mapStepsToPhaseSegments(steps: unknown[], totalDurationSec = 0): PhaseSegment[] {
  const list = Array.isArray(steps) ? steps : []
  if (list.length === 0) return []
  const durations = list.map((step) => stepDurationSec(step) ?? 0)
  const summed = durations.reduce((sum, duration) => sum + duration, 0)
  const total = totalDurationSec > 0 ? totalDurationSec : summed
  return list.map((step, index) => {
    const duration = durations[index] ?? 0
    return {
      key: getString(asObject(step), 'id') ?? getString(asObject(step), 'name') ?? `step-${index + 1}`,
      ratio: total > 0 ? duration / total : 1 / list.length,
      tone: phaseTone(step),
      status: phaseSegmentStatus(step),
    }
  })
}

function currentPhaseName(steps: unknown[]): string | undefined {
  const active = steps.find((step) => resolveStepState(step) === 'active')
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

  return {
    id,
    workflow: getString(projection, 'workflowType') ?? getString(projection, 'title') ?? 'workflow',
    status: mapWorkflowStateToRunStatus(getString(projection, 'status'), steps),
    currentPhase: currentPhaseName(steps),
    goal,
    // Real cost wins; otherwise fall back to the projection/snapshot cost, else 0.
    costUsd: realCost?.cost ?? getNumber(projection, 'costUsd') ?? getNumber(snapshot, 'costUsd') ?? 0,
    // `unknownCostCount` is preserved verbatim (never folded into cost as 0).
    unknownCostCount: realCost?.unknownCostCount ?? 0,
    durationSec,
    tokens: getNumber(projection, 'tokens') ?? 0,
    phases: mapStepsToPhaseSegments(steps, durationSec),
  }
}

// ---------------------------------------------------------------------------
// Timeline lanes
// ---------------------------------------------------------------------------

const MIN_BLOCK_SEC = 2

function stepBlockStatus(step: unknown): TimelineBlock['status'] {
  const state = resolveStepState(step)
  return state === 'active' || state === 'failed' ? 'running' : 'done'
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
 * dedicated `request` column, matching the reference cockpit layout.
 */
export function mapProjectionToLanes(input: unknown, timing?: unknown): TimelineLane[] {
  const snapshot = asObject(input) ?? {}
  const projection = asObject(snapshot['projection']) ?? snapshot
  const steps = asArray(projection['steps'])
  if (steps.length === 0) return []

  const timingObj = asObject(timing)
  const starts = steps
    .map((step) => toEpochMs(asObject(step)?.['startedAt']))
    .filter((value): value is number => value !== null)
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

  const placements: { lane: LaneDescriptor; block: TimelineBlock; isRequest: boolean; contextPct?: number }[] = []
  let cursorSec = 0

  for (const entry of ordered) {
    const startSec = entry.start !== null ? Math.max((entry.start - t0) / 1000, 0) : cursorSec
    const rawDuration = entry.duration ?? 0
    const endSec = Math.max(startSec + Math.max(rawDuration, MIN_BLOCK_SEC), startSec + MIN_BLOCK_SEC)
    cursorSec = Math.max(cursorSec, endSec)

    const block: TimelineBlock = {
      label: getString(entry.obj, 'name') ?? getString(entry.obj, 'id') ?? 'step',
      startSec,
      endSec,
      status: stepBlockStatus(entry.obj),
    }
    const description = getString(entry.obj, 'description')
    if (description) block.description = description
    const ticksSec = normalizeTicks(entry.obj['ticks'], t0, startSec, endSec)
    if (ticksSec) block.ticksSec = ticksSec
    const errorTicksSec = normalizeTicks(entry.obj['errorTicks'], t0, startSec, endSec)
    if (errorTicksSec) block.errorTicksSec = errorTicksSec

    const stepId = getString(entry.obj, 'id')
    placements.push({
      lane: classifyLane(entry.obj),
      block,
      isRequest: stepId === 'request' || block.label === 'request',
      contextPct: getNumber(entry.obj, 'contextPct'),
    })
  }

  const lanes = new Map<string, TimelineLane>()
  for (const placement of placements) {
    let lane = lanes.get(placement.lane.id)
    if (!lane) {
      lane = {
        id: placement.lane.id,
        label: placement.lane.label,
        subtitle: placement.lane.kind === 'agent' ? placement.lane.label : 'workspace',
        kind: placement.lane.kind === 'human' ? 'human' : placement.lane.kind === 'code' ? 'workspace' : 'agent',
        tone: toneForActor(placement.lane.kind),
        blocks: [],
      }
      if (placement.lane.kind === 'agent' && placement.contextPct !== undefined) lane.contextPct = placement.contextPct
      lanes.set(placement.lane.id, lane)
    }
    if (placement.lane.kind === 'human' && placement.isRequest && !lane.request) {
      lane.request = placement.block
    } else {
      lane.blocks.push(placement.block)
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
      status: state === 'active' ? 'running' : state === 'pending' ? 'pending' : 'done',
    }
    const duration = stepDurationSec(obj)
    if (duration !== null) result.durationSec = Math.round(duration)
    return result
  })
}

function buildPhaseDetail(steps: unknown[], fallbackStatus: RunStatus): PhaseDetail {
  const active = steps.find((step) => resolveStepState(step) === 'active') ?? steps[steps.length - 1]
  const obj = asObject(active) ?? {}
  const responsibility = asObject(obj['responsibility'])
  const duration = stepDurationSec(obj) ?? 0
  const description = getString(obj, 'description')

  return {
    name: getString(obj, 'name') ?? getString(obj, 'id') ?? 'phase',
    status: getString(obj, 'status') ? mapWorkflowStateToRunStatus(getString(obj, 'status'), [obj]) : fallbackStatus,
    durationSec: Math.round(duration),
    owner: getString(responsibility, 'name') ?? actorName(obj),
    kind: getString(responsibility, 'kind') ?? classifyActorKind(obj),
    attempt: '0/0',
    sections: [
      { label: "Configuration de l'agent" },
      { label: 'Description', ...(description ? { body: description } : {}) },
      { label: 'Prompts compilés', count: 0 },
      { label: 'Gates', count: 0 },
      { label: 'Sorties', count: 0 },
    ],
  }
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
 * Map a workflow snapshot (+ optional timing / evidence / metrics payloads) to a
 * complete {@link SessionDetail}. Any missing enrichment simply yields defaults.
 */
export function mapProjectionToSessionDetail(
  workflow: unknown,
  timing?: unknown,
  evidence?: unknown,
  metrics?: unknown
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

  const id = getString(snapshot, 'workflowId') ?? getString(projection, 'workflowId') ?? 'unknown'
  const status = mapWorkflowStateToRunStatus(getString(projection, 'status'), steps)
  const lanes = mapProjectionToLanes(snapshot, timing)
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

  return {
    id,
    sandbox:
      getString(relations, 'ticket') ?? getString(controller, 'caseId') ?? getString(snapshot, 'namespaceId') ?? id,
    goal: getString(projection, 'goal') ?? getString(projection, 'title') ?? '',
    status,
    startedAt:
      getString(timingObj, 'startedAt') ??
      getString(projection, 'startedAt') ??
      firstStartedAt(steps) ??
      new Date().toISOString(),
    workflow: getString(projection, 'workflowType') ?? getString(projection, 'title') ?? 'workflow',
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
    events: mapEvidenceToEvents(evidenceItems, steps),
    phase: buildPhaseDetail(steps, status),
  }
}
