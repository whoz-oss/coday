/**
 * Workflow and session projection mappers.
 * Orchestrates all sub-modules to produce SessionDetail and FactoryRun.
 */

import {
  AgentAttempt,
  AgentQuestion,
  CorrectionCycle,
  FactoryRun,
  RunStatus,
  SandboxStatus,
  SessionDetail,
  SessionStep,
  StepExecution,
} from '../models'
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
  readControllerRequest,
  mapWorkflowStateToRunStatus,
  resolveTimelineOriginMs,
} from './timeline'
import {
  buildLaunchRequestDetail,
  buildPhaseDetail,
  filterEventsForSelection,
  mapProjectionToPhaseByStepId,
  mapProjectionToPhaseByAttemptId,
  mapEvidenceToEvents,
  mapInteractionsToEvents,
} from './phase-detail'

// ---------------------------------------------------------------------------
// Run status helpers
// ---------------------------------------------------------------------------

const RUN_WORKING_STATES = new Set(['running', 'active', 'waiting_human', 'needs_research'])
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

function hasCaseId(attempt: AgentAttempt): boolean {
  return attempt.caseId.trim().length > 0
}

/**
 * Resolve the main AgentOS case of a run, i.e. the one the dashboard Conversation button opens.
 *
 * 1. the controller case exposed by the snapshot (parent-Run fragments, runs created with an explicit case);
 * 2. the case of the attempt of the entry agent step (the only agent step without `dependsOn`): Factory runs that
 *    step in the run root case, which the snapshot does not expose;
 * 3. the case of the earliest known attempt (by `createdAt`, unknown last, then `attemptNumber`).
 *
 * Returns `undefined` while no agent step has been dispatched.
 */
export function resolveMainCaseId(snapshot: unknown, attempts: AgentAttempt[] = []): string | undefined {
  const controllerCaseId = controllerCaseIdOf(snapshot)
  if (controllerCaseId?.trim()) return controllerCaseId

  const withCase = attempts.filter(hasCaseId)
  if (withCase.length === 0) return undefined

  const obj = asObject(snapshot)
  const projection = asObject(obj?.['projection']) ?? obj
  const entryAgentStepIds = asArray(projection?.['steps']).flatMap((step): string[] => {
    const stepObj = asObject(step)
    const isAgent = getString(asObject(stepObj?.['responsibility']), 'kind') === 'agent'
    const stepId = getString(stepObj, 'id')
    return isAgent && stepId && asArray(stepObj?.['dependsOn']).length === 0 ? [stepId] : []
  })
  if (entryAgentStepIds.length === 1) {
    const entryAttempt = withCase
      .filter((attempt) => attempt.stepId === entryAgentStepIds[0])
      .sort((left, right) => left.attemptNumber - right.attemptNumber)[0]
    if (entryAttempt) return entryAttempt.caseId
  }

  const createdAtMs = (attempt: AgentAttempt): number => {
    const parsed = Date.parse(attempt.createdAt ?? '')
    return Number.isFinite(parsed) ? parsed : Number.POSITIVE_INFINITY
  }
  const earliest = [...withCase].sort((left, right) => {
    const leftMs = createdAtMs(left)
    const rightMs = createdAtMs(right)
    if (leftMs !== rightMs) return leftMs < rightMs ? -1 : 1
    return left.attemptNumber - right.attemptNumber
  })[0]
  return earliest?.caseId
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
        : state === 'skipped'
          ? 'skipped'
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
  agentQuestions?: AgentQuestion[],
  attemptsLoadError?: string
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
  const stepExecutions = extractStepExecutions(projection['stepExecutions'])
  const correction = extractCorrection(projection['correction'])
  const mappedAllowedActions = extractAllowedActions(actions)
  const mappedBlockers = extractBlockers(actions)
  const blockedStepIds = new Set(mappedBlockers.map((blocker) => blocker.stepId).filter((id): id is string => !!id))

  const cancelAttemptAction = mappedAllowedActions.find(
    (action) => action.type === 'cancel_attempt' && action.attemptId !== undefined
  )
  const runningAttempt = mappedAttempts.find((attempt) => attempt.status === 'running' || attempt.status === 'starting')
  const activeAttemptId = cancelAttemptAction?.attemptId ?? runningAttempt?.attemptId
  const activeAttemptRevision = cancelAttemptAction?.expectedRevision ?? runningAttempt?.revision

  const id = getString(snapshot, 'workflowId') ?? getString(projection, 'workflowId') ?? 'unknown'
  const controllerCaseId = controllerCaseIdOf(snapshot)
  const mainCaseId = resolveMainCaseId(snapshot, mappedAttempts)
  const projectedStatus = mapWorkflowStateToRunStatus(getString(projection, 'status'), steps)
  const derivedStatus = mapWorkflowStateToRunStatus(undefined, steps)
  const latestAttemptByStep = new Map<string, import('../models').AgentAttempt>()
  for (const attempt of mappedAttempts) {
    const prev = latestAttemptByStep.get(attempt.stepId)
    if (!prev || attempt.attemptNumber >= prev.attemptNumber) {
      latestAttemptByStep.set(attempt.stepId, attempt)
    }
  }
  const latestAttempts = [...latestAttemptByStep.values()]
  const hasRunningAttempt = latestAttempts.some(
    (attempt) => attempt.status.toLowerCase() === 'running' || attempt.status.toLowerCase() === 'starting'
  )
  const hasNeedsResearchAttempt =
    !hasRunningAttempt &&
    latestAttempts.some(
      (attempt) =>
        attempt.status.toLowerCase() === 'indeterminate' &&
        attempt.failureCode?.toUpperCase() === 'AGENT_NEEDS_RESEARCH'
    )
  const stepStates = steps.map(resolveStepState)
  // An outcome-managed run (execution ledger present) can hold a failed step while the backend is still replaying
  // a correction: its projected status is then the authority and only a published `failed` ends the run.
  const outcomeManaged = stepExecutions.length > 0
  const stepsForceFailedStatus = derivedStatus === 'failed' && (!outcomeManaged || projectedStatus === 'failed')
  const isActivelyRunning = stepStates.includes('running') || hasRunningAttempt
  const waitingHuman =
    !isActivelyRunning &&
    (correction?.status === 'waiting_human' ||
      stepStates.includes('waiting_human') ||
      hasNeedsResearchAttempt ||
      latestAttempts.some((attempt) => attempt.status === 'waiting_human') ||
      (agentQuestions ?? []).some((q) => !q.answered))
  const status: RunStatus =
    isActivelyRunning || waitingHuman ? 'running' : stepsForceFailedStatus ? 'failed' : projectedStatus
  const activelyRunning = isActivelyRunning
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
  const timelineOriginMs = resolveTimelineOriginMs(enrichedSnapshot, timing, mappedAttempts, stepExecutions)
  const timelineOriginAt = new Date(timelineOriginMs).toISOString()
  const lanes = mapProjectionToLanes(enrichedSnapshot, timing, mappedAttempts, stepExecutions)
  const laneEnd = lanes.reduce((max, lane) => {
    const requestEnd = lane.request?.endSec ?? 0
    const blocksEnd = lane.blocks.reduce((end, block) => Math.max(end, block.endSec), 0)
    return Math.max(max, requestEnd, blocksEnd)
  }, 0)

  const durations = steps.map((step) => stepDurationSec(step)).filter((value): value is number => value !== null)
  const summedDuration = durations.reduce((sum, value) => sum + value, 0)
  const attemptsHaveAuthoritativeDuration = mappedAttempts.some(
    (attempt) => attempt.activeDurationMs !== undefined || attempt.activeSince !== undefined
  )
  const authoritativeAttemptDurationSec = mappedAttempts.reduce((sum, attempt) => {
    const closedMs = Math.max(attempt.activeDurationMs ?? 0, 0)
    const activeSinceMs = Date.parse(attempt.activeSince ?? '')
    const openMs =
      attempt.status === 'running' && Number.isFinite(activeSinceMs) ? Math.max(Date.now() - activeSinceMs, 0) : 0
    return sum + (closedMs + openMs) / 1000
  }, 0)
  const legacyRunningDuration: number = activelyRunning
    ? steps.reduce<number>((sum, step) => {
        if (resolveStepState(step) !== 'running') return sum
        const startedAtMs = Date.parse(getString(asObject(step), 'startedAt') ?? '')
        return Number.isFinite(startedAtMs) ? sum + Math.max((Date.now() - startedAtMs) / 1000, 0) : sum
      }, 0)
    : 0
  // Attempts are authoritative when their duration contract is present — even
  // when the value is zero. This avoids double-counting the matching steps.
  const activeDurationSec = Math.round(
    attemptsHaveAuthoritativeDuration ? authoritativeAttemptDurationSec : summedDuration + legacyRunningDuration
  )
  // Only the run-wide realCost counters are cumulative usage. `liveTokens`
  // belongs to the live cost feed and is deliberately never shown as context
  // or total tokens. Missing counters remain unknown, while zero is retained.
  const tokens = realCost?.totalTokens ?? null
  const tokensRead = realCost?.inputTokens ?? null
  const tokensWritten = realCost?.outputTokens ?? null

  const phaseByStepId = mapProjectionToPhaseByStepId(
    steps,
    status,
    mappedInteractions,
    mappedAttempts,
    evidenceItems,
    attemptsLoadError
  )
  const controllerRequest = readControllerRequest(snapshot, projection)
  const instance = asObject(snapshot['instance'])
  const createdAt =
    getString(snapshot, 'createdAt') ??
    getString(projection, 'createdAt') ??
    getString(instance, 'createdAt') ??
    controllerRequest?.observedAt ??
    timelineOriginAt
  const launchRequest = buildLaunchRequestDetail({
    title: getString(projection, 'title') ?? id,
    createdAt,
    namespaceId: controllerRequest?.namespaceId ?? namespaceOf(snapshot),
    workflowId: id,
    workflowType: getString(projection, 'workflowType'),
    author: controllerRequest?.actorId,
    text: controllerRequest?.text ?? controllerRequest?.prompt,
  })
  const phaseByAttemptId = mapProjectionToPhaseByAttemptId(
    steps,
    status,
    mappedInteractions,
    mappedAttempts,
    evidenceItems,
    attemptsLoadError
  )
  const phaseByExecutionId = new Map<string, import('../models').PhaseDetail>()
  for (const execution of stepExecutions) {
    const executionEvidence = evidenceForExecution(evidenceItems, execution)
    const executionInteractions = mappedInteractions.filter(
      (interaction) => interaction.executionId === execution.executionId
    )
    const executionAttempt = execution.attemptId
      ? mappedAttempts.filter((attempt) => attempt.attemptId === execution.attemptId)
      : []
    const executionScopedSteps = steps.map((raw) => {
      const step = asObject(raw)
      return getString(step, 'id') === execution.stepId
        ? { ...step, status: execution.status, startedAt: execution.startedAt, completedAt: execution.completedAt }
        : raw
    })
    // Each ledger entry gets its own timestamps, evidence/interactions and never resolves a newer attempt.
    phaseByExecutionId.set(
      execution.executionId,
      buildPhaseDetail(
        executionScopedSteps,
        status,
        executionInteractions,
        executionAttempt,
        executionEvidence,
        execution.stepId,
        execution.attemptId
      )
    )
  }

  // Pre-compute per-step and per-attempt event slices so the UI can show
  // filtered events without re-scanning evidence on every selection change.
  const eventsByStepId = new Map<string, import('../models').RunEvent[]>()
  const eventsByAttemptId = new Map<string, import('../models').RunEvent[]>()
  const eventsByExecutionId = new Map<string, import('../models').RunEvent[]>()

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
  for (const execution of stepExecutions) {
    const executionInteractions = mappedInteractions.filter(
      (interaction) => interaction.executionId === execution.executionId
    )
    eventsByExecutionId.set(execution.executionId, [
      ...mapEvidenceToEvents(evidenceForExecution(evidenceItems, execution), []),
      ...mapInteractionsToEvents(executionInteractions),
    ])
  }

  const relations =
    asObject(snapshot['relations']) ?? asObject(asObject(snapshot['instance'])?.['relations']) ?? undefined

  const result: SessionDetail = {
    ...(steps.some((raw) => asObject(raw)?.['decision'] || asObject(raw)?.['outcomeTransitions'])
      ? { routingSteps: steps as import('../factory-api.service').FullWorkflowStep[] }
      : {}),
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
    // Costs are analytics-owned. Do not revive legacy projection/budget values
    // when analytics explicitly reports an unavailable value.
    costUsd: realCost?.cost ?? null,
    unknownCostCount: realCost?.unknownCostCount ?? null,
    durationSec: activeDurationSec,
    tokens,
    tokensRead,
    tokensWritten,
    steps: mapStepsToSessionSteps(steps, blockedStepIds),
    lanes,
    nowSec: Math.round(
      activelyRunning ? Math.max(laneEnd, Math.max((Date.now() - timelineOriginMs) / 1000, 0)) : laneEnd
    ),
    events: [...mapEvidenceToEvents(evidenceItems, steps), ...mapInteractionsToEvents(mappedInteractions)],
    phase: buildPhaseDetail(
      steps,
      status,
      mappedInteractions,
      mappedAttempts,
      evidenceItems,
      undefined,
      undefined,
      attemptsLoadError
    ),
    launchRequest,
    phaseByStepId,
    phaseByAttemptId,
    phaseByExecutionId,
    interactions: mappedInteractions,
    attempts: mappedAttempts,
    ...(attemptsLoadError ? { attemptsLoadError } : {}),
    cacheReadTokens: realCost?.cacheReadTokens,
    cacheWriteTokens: realCost?.cacheWriteTokens,
    ...(stepExecutions.length ? { stepExecutions } : {}),
    ...(correction ? { correction } : {}),
    allowedActions: mappedAllowedActions,
    blockers: mappedBlockers,
    agentQuestions: agentQuestions ?? [],
    ...(activeAttemptId !== undefined ? { activeAttemptId } : {}),
    ...(activeAttemptRevision !== undefined ? { activeAttemptRevision } : {}),
    ...(controllerCaseId !== undefined ? { controllerCaseId } : {}),
    ...(mainCaseId !== undefined ? { mainCaseId } : {}),
    eventsByStepId,
    eventsByAttemptId,
    eventsByExecutionId,
  }
  return result
}

function evidenceForExecution(evidenceItems: unknown[], execution: StepExecution): unknown[] {
  return evidenceItems.filter((raw) => {
    const obj = asObject(raw)
    const facts = asObject(obj?.['facts'])
    const evidenceId = getString(obj, 'evidenceId') ?? getString(obj, 'id')
    return (
      getString(obj, 'executionId') === execution.executionId ||
      getString(facts, 'executionId') === execution.executionId ||
      (execution.resultEvidenceId !== undefined && evidenceId === execution.resultEvidenceId) ||
      (execution.attemptId !== undefined &&
        (getString(obj, 'attemptId') === execution.attemptId || getString(facts, 'attemptId') === execution.attemptId))
    )
  })
}

function extractStepExecutions(value: unknown): StepExecution[] {
  return asArray(value).flatMap((raw): StepExecution[] => {
    const obj = asObject(raw)
    const executionId = getString(obj, 'executionId')
    const stepId = getString(obj, 'stepId')
    const executionNumber = getNumber(obj, 'executionNumber')
    const status = getString(obj, 'status')
    if (!executionId || !stepId || executionNumber === undefined || !status) return []
    const cycleNumber = getNumber(obj, 'cycleNumber')
    const startedAt = getString(obj, 'startedAt')
    const completedAt = getString(obj, 'completedAt')
    const attemptId = getString(obj, 'attemptId')
    const resultEvidenceId = getString(obj, 'resultEvidenceId')
    const triggeringExecutionId = getString(obj, 'triggeringExecutionId')
    return [
      {
        executionId,
        stepId,
        executionNumber,
        status,
        ...(cycleNumber !== undefined ? { cycleNumber } : {}),
        ...(startedAt ? { startedAt } : {}),
        ...(completedAt ? { completedAt } : {}),
        ...(attemptId ? { attemptId } : {}),
        ...(resultEvidenceId ? { resultEvidenceId } : {}),
        ...(triggeringExecutionId ? { triggeringExecutionId } : {}),
      },
    ]
  })
}

function extractCorrection(value: unknown): CorrectionCycle | undefined {
  const obj = asObject(value)
  const cycleId = getString(obj, 'cycleId')
  const cycleNumber = getNumber(obj, 'cycleNumber')
  const maxCycles = getNumber(obj, 'maxCycles')
  const status = getString(obj, 'status')
  const triggeringStepId = getString(obj, 'triggeringStepId')
  const returnTargetStepId = getString(obj, 'returnTargetStepId')
  if (
    !obj ||
    !cycleId ||
    cycleNumber === undefined ||
    maxCycles === undefined ||
    !status ||
    !triggeringStepId ||
    !returnTargetStepId
  )
    return undefined
  const triggeringAttemptId = getString(obj, 'triggeringAttemptId')
  const triggeringExecutionId = getString(obj, 'triggeringExecutionId')
  return {
    cycleId,
    cycleNumber,
    maxCycles,
    status,
    triggeringStepId,
    returnTargetStepId,
    ...(triggeringAttemptId ? { triggeringAttemptId } : {}),
    ...(triggeringExecutionId ? { triggeringExecutionId } : {}),
  }
}

function mergeCodeEvidenceIntoSteps(steps: unknown[], evidenceItems: unknown[]): unknown[] {
  // Evidence recorded for a previous execution of a replayed step must not describe its current execution.
  const currentExecutionByStep = new Map<string, string>()
  for (const raw of steps) {
    const step = asObject(raw)
    const stepId = getString(step, 'id')
    const executionId = getString(step, 'executionId')
    if (stepId && executionId) currentExecutionByStep.set(stepId, executionId)
  }
  const latestByStep = new Map<string, Record<string, unknown>>()
  for (const raw of evidenceItems) {
    const item = asObject(raw)
    const kind = getString(item, 'kind')
    if (kind !== 'code-verification' && kind !== 'session-step') continue
    const stepId = getString(item, 'stepId') ?? getString(asObject(item?.['facts']), 'stepId')
    if (!stepId) continue
    const facts = asObject(item?.['facts'])
    if (!facts) continue
    const evidenceExecutionId = getString(facts, 'executionId') ?? getString(item, 'executionId')
    const currentExecutionId = currentExecutionByStep.get(stepId)
    if (evidenceExecutionId && currentExecutionId && evidenceExecutionId !== currentExecutionId) continue
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
        : recordedStatus === 'needs_research'
          ? 'waiting_human'
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
  const mainCaseId = resolveMainCaseId(snapshot)
  if (mainCaseId) factoryRun.mainCaseId = mainCaseId
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
        unknownCostCount: detail.unknownCostCount,
        totalTokens: detail.tokens ?? undefined,
      },
    },
    undefined,
    blockedStepIds
  )
  summary.status = detail.status
  summary.durationSec = detail.durationSec
  summary.tokens = detail.tokens
  summary.costUsd = detail.costUsd
  summary.unknownCostCount = detail.unknownCostCount
  summary.waitingHuman = detail.executionState === 'waiting_human'
  if (detail.mainCaseId) summary.mainCaseId = detail.mainCaseId
  summary.cardState =
    detail.executionState === 'waiting_human'
      ? 'waiting_human'
      : detail.status === 'failed'
        ? 'failed'
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
