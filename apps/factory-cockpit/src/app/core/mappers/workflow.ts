/**
 * Workflow and session projection mappers.
 * Orchestrates all sub-modules to produce SessionDetail and FactoryRun.
 */

import { AgentQuestion, FactoryRun, RunStatus, SandboxStatus, SessionDetail, SessionStep } from '../models'
import { asArray, asObject, getString, getNumber } from './utils'
import { classifyActorKind, resolveStepState, stepDurationSec, toneForActor } from './step-classification'
import {
  extractAttempts,
  extractAllowedActions,
  extractBlockers,
  extractInteractions,
  extractRealCost,
} from './actions'
import {
  mapProjectionToLanes,
  mapProjectionToRunSummary,
  mapWorkflowStateToRunStatus,
  resolveTimelineOriginMs,
} from './timeline'
import {
  buildPhaseDetail,
  filterEventsForSelection,
  mapProjectionToPhaseByStepId,
  mapEvidenceToEvents,
  mapInteractionsToEvents,
  sumFacts,
} from './phase-detail'

// ---------------------------------------------------------------------------
// Run status helpers
// ---------------------------------------------------------------------------

const RUN_WORKING_STATES = new Set(['running', 'active', 'waiting_human'])
const RUN_IDLE_STATES = new Set(['idle', 'ready', 'pending', 'queued'])

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

// ---------------------------------------------------------------------------
// Identity helpers
// ---------------------------------------------------------------------------

export function workflowIdOf(snapshot: unknown): string | undefined {
  const obj = asObject(snapshot)
  const projection = asObject(obj?.['projection'])
  return getString(obj, 'workflowId') ?? getString(projection, 'workflowId')
}

export function namespaceOf(snapshot: unknown): string | undefined {
  const obj = asObject(snapshot)
  const instance = asObject(obj?.['instance'])
  return getString(obj, 'namespaceId') ?? getString(instance, 'namespaceId')
}

export function controllerCaseIdOf(snapshot: unknown): string | undefined {
  const obj = asObject(snapshot)
  const controller = asObject(obj?.['controllerExecution'])
  return getString(controller, 'caseId')
}

// ---------------------------------------------------------------------------
// Session steps (phase list)
// ---------------------------------------------------------------------------

function phaseToneForStep(step: unknown): import('../models').Tone {
  return ['failed', 'indeterminate'].includes(resolveStepState(step)) ? 'red' : toneForActor(classifyActorKind(step))
}

function mapStepsToSessionSteps(steps: unknown[], blockedStepIds: ReadonlySet<string> = new Set()): SessionStep[] {
  return steps.map((step, index) => {
    const obj = asObject(step) ?? {}
    const state = resolveStepState(obj)
    const result: SessionStep = {
      key: getString(obj, 'id') ?? getString(obj, 'name') ?? `step-${index + 1}`,
      tone: phaseToneForStep(obj),
      status: blockedStepIds.has(getString(obj, 'id') ?? getString(obj, 'name') ?? `step-${index + 1}`)
        ? 'blocked'
        : state === 'waiting_human'
          ? 'waiting_human'
          : state === 'running'
            ? 'running'
            : state === 'failed'
              ? 'failed'
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
// Session detail
// ---------------------------------------------------------------------------

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
  const projectedSteps = asArray(projection['steps'])
  const evidenceItems = asArray(asObject(evidence)?.['items'])
  const steps = mergeCodeEvidenceIntoSteps(projectedSteps, evidenceItems)
  const controller = asObject(snapshot['controllerExecution'])
  const timingObj = asObject(timing)
  const metricsObj = asObject(metrics)
  const realCost = extractRealCost(metricsObj)
  const mappedInteractions = extractInteractions(interactions)
  const mappedAttempts = extractAttempts(attempts)
  const mappedAllowedActions = extractAllowedActions(actions)
  const mappedBlockers = extractBlockers(actions)
  const blockedStepIds = new Set(mappedBlockers.map((blocker) => blocker.stepId).filter((id): id is string => !!id))

  const cancelAttemptAction = mappedAllowedActions.find(
    (action) => action.type === 'cancel_attempt' && action.attemptId !== undefined
  )
  const runningAttempt = mappedAttempts.find((attempt) => attempt.status === 'running')
  const activeAttemptId = cancelAttemptAction?.attemptId ?? runningAttempt?.attemptId
  const activeAttemptRevision = cancelAttemptAction?.expectedRevision ?? runningAttempt?.revision

  const id = getString(snapshot, 'workflowId') ?? getString(projection, 'workflowId') ?? 'unknown'
  const controllerCaseId = controllerCaseIdOf(snapshot)
  const projectedStatus = mapWorkflowStateToRunStatus(getString(projection, 'status'), steps)
  const derivedStatus = mapWorkflowStateToRunStatus(undefined, steps)
  const status = derivedStatus === 'failed' ? 'failed' : projectedStatus
  const stepStates = steps.map(resolveStepState)
  const waitingHuman =
    stepStates.includes('waiting_human') || mappedAttempts.some((attempt) => attempt.status === 'waiting_human')
  const activelyRunning = !waitingHuman && stepStates.includes('running')
  const executionState: import('../models').TimelineStepStatus = waitingHuman
    ? 'waiting_human'
    : activelyRunning
      ? 'running'
      : status === 'succeeded'
        ? 'completed'
        : status === 'failed'
          ? 'failed'
          : 'pending'

  const enrichedSnapshot = { ...snapshot, projection: { ...projection, steps } }
  const timelineOriginMs = resolveTimelineOriginMs(enrichedSnapshot, timing, mappedAttempts)
  const timelineOriginAt = new Date(timelineOriginMs).toISOString()
  const lanes = mapProjectionToLanes(enrichedSnapshot, timing, mappedAttempts)
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

  const phaseByStepId = mapProjectionToPhaseByStepId(steps, status, mappedInteractions, mappedAttempts, evidenceItems)

  // Pre-compute per-step and per-attempt event slices so the UI can show
  // filtered events without re-scanning evidence on every selection change.
  const eventsByStepId = new Map<string, import('../models').RunEvent[]>()
  const eventsByAttemptId = new Map<string, import('../models').RunEvent[]>()

  for (const step of steps) {
    const stepObj = asObject(step)
    const stepId = getString(stepObj, 'id') ?? getString(stepObj, 'key')
    if (!stepId) continue
    eventsByStepId.set(
      stepId,
      filterEventsForSelection([], evidenceItems, mappedInteractions, mappedAttempts, stepId, undefined)
    )
  }

  for (const attempt of mappedAttempts) {
    eventsByAttemptId.set(
      attempt.attemptId,
      filterEventsForSelection([], evidenceItems, mappedInteractions, mappedAttempts, attempt.stepId, attempt.attemptId)
    )
  }

  const relations =
    asObject(snapshot['relations']) ?? asObject(asObject(snapshot['instance'])?.['relations']) ?? undefined

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
    timelineOriginAt,
    workflow: getString(projection, 'title') ?? id,
    costUsd:
      realCost?.cost ??
      getNumber(projection, 'costUsd') ??
      getNumber(metricsObj, 'costUsd') ??
      getNumber(snapshot, 'costUsd') ??
      0,
    unknownCostCount: realCost?.unknownCostCount ?? 0,
    durationSec: Math.max(Math.round(summedDuration), Math.round(laneEnd)),
    tokens,
    tokensRead,
    tokensWritten,
    steps: mapStepsToSessionSteps(steps, blockedStepIds),
    lanes,
    nowSec: Math.round(
      activelyRunning ? Math.max(laneEnd, Math.max((Date.now() - timelineOriginMs) / 1000, 0)) : laneEnd
    ),
    events: [...mapEvidenceToEvents(evidenceItems, steps), ...mapInteractionsToEvents(mappedInteractions)],
    phase: buildPhaseDetail(steps, status, mappedInteractions, mappedAttempts, evidenceItems),
    phaseByStepId,
    interactions: mappedInteractions,
    attempts: mappedAttempts,
    allowedActions: mappedAllowedActions,
    blockers: mappedBlockers,
    agentQuestions: agentQuestions ?? [],
    ...(activeAttemptId !== undefined ? { activeAttemptId } : {}),
    ...(activeAttemptRevision !== undefined ? { activeAttemptRevision } : {}),
    ...(controllerCaseId !== undefined ? { controllerCaseId } : {}),
    eventsByStepId,
    eventsByAttemptId,
  }
  return result
}

function mergeCodeEvidenceIntoSteps(steps: unknown[], evidenceItems: unknown[]): unknown[] {
  const latestByStep = new Map<string, Record<string, unknown>>()
  for (const raw of evidenceItems) {
    const item = asObject(raw)
    const kind = getString(item, 'kind')
    if (kind !== 'code-verification' && kind !== 'session-step') continue
    const stepId = getString(item, 'stepId') ?? getString(asObject(item?.['facts']), 'stepId')
    if (!stepId) continue
    const facts = asObject(item?.['facts'])
    if (!facts) continue
    const status = getString(facts, 'status') ?? getString(item, 'outcome')
    const temporal = getNumber(facts, 'durationMs') !== undefined || getString(facts, 'startedAt') !== undefined
    if (!temporal && status !== 'running') continue
    latestByStep.set(stepId, { ...facts, evidenceStatus: status })
  }
  return steps.map((raw) => {
    const step = asObject(raw)
    const stepId = getString(step, 'id')
    if (!step || !stepId) return raw
    const facts = latestByStep.get(stepId)
    if (!facts) return raw
    const durationMs = getNumber(step, 'durationMs') ?? getNumber(facts, 'durationMs')
    const startedAt = getString(step, 'startedAt') ?? getString(facts, 'startedAt')
    const completedAt = getString(step, 'completedAt') ?? getString(facts, 'completedAt')
    const status = resolveStepState(step)
    const recordedStatus = getString(facts, 'evidenceStatus')
    const evidenceStatus =
      recordedStatus === 'running'
        ? 'running'
        : getNumber(facts, 'exitCode') === 0 && facts['timedOut'] !== true
          ? 'completed'
          : 'failed'
    return {
      ...step,
      ...(durationMs !== undefined ? { durationMs } : {}),
      ...(startedAt ? { startedAt } : {}),
      ...(completedAt ? { completedAt } : {}),
      status: status === 'pending' || status === 'ready' ? evidenceStatus : status,
    }
  })
}

function firstStartedAt(steps: unknown[]): string | undefined {
  for (const step of steps) {
    const startedAt = getString(asObject(step), 'startedAt')
    if (startedAt) return startedAt
  }
  return undefined
}

// ---------------------------------------------------------------------------
// Factory run
// ---------------------------------------------------------------------------

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
    status: forcedStatus ?? (run.status === 'failed' ? 'failed' : deriveRunStatus(state, run.status)),
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

// Re-export for backward compat via facade
export { mapProjectionToRunSummary, mapWorkflowStateToRunStatus, mapProjectionToLanes, mapProjectionToPhaseByStepId }

/** Build the card summary from the already-enriched SessionDetail truth. */
export function mapSessionDetailToRunSummary(snapshot: unknown, detail: SessionDetail): import('../models').RunSummary {
  const blockedStepIds = new Set(
    (detail.blockers ?? []).map((blocker) => blocker.stepId).filter((id): id is string => !!id)
  )
  const summary = mapProjectionToRunSummary(
    snapshot,
    {
      realCost: {
        cost: detail.costUsd,
        unknownCostCount: detail.unknownCostCount ?? 0,
        liveTokens: detail.tokens,
      },
    },
    undefined,
    blockedStepIds
  )
  summary.status = detail.status
  summary.durationSec = detail.durationSec
  summary.tokens = detail.tokens
  summary.costUsd = detail.costUsd
  summary.unknownCostCount = detail.unknownCostCount ?? 0
  summary.waitingHuman = detail.executionState === 'waiting_human'
  summary.cardState =
    detail.status === 'failed'
      ? 'failed'
      : detail.executionState === 'waiting_human'
        ? 'waiting_human'
        : blockedStepIds.size > 0
          ? 'blocked'
          : detail.activelyRunning
            ? 'running'
            : detail.status === 'succeeded'
              ? 'succeeded'
              : 'pending'
  summary.phases = detail.steps.map((step) => ({
    key: step.key,
    ratio: detail.durationSec > 0 ? (step.durationSec ?? 0) / detail.durationSec : 1 / Math.max(detail.steps.length, 1),
    tone: step.tone,
    status: step.status,
  }))
  return summary
}
