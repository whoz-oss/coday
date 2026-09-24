import { WORKFLOW_STATUSES } from './workflow-projection.mjs'

const STATUS_SET = new Set(WORKFLOW_STATUSES)
const ACTIVE = new Set(['ready', 'running'])

export const WORKFLOW_DURATION_BUCKET = Object.freeze({
  ready: 'activeMs',
  running: 'activeMs',
  waiting_human: 'waitingHumanMs',
  blocked: 'blockedMs',
  pending: null,
  completed: null,
  failed: null,
  cancelled: null,
})

function instant(value) {
  if (typeof value !== 'string') return null
  const milliseconds = Date.parse(value)
  return Number.isFinite(milliseconds) ? milliseconds : null
}

function emptyState(id) {
  return {
    id,
    createdAt: undefined,
    startedAt: undefined,
    firstCompletedAt: undefined,
    lastCompletedAt: undefined,
    lastActivityAt: undefined,
    activeMs: 0,
    waitingHumanMs: 0,
    blockedMs: 0,
    transitionCount: 0,
    attemptCount: 0,
    currentStatus: undefined,
    currentStatusSince: undefined,
    lastMs: undefined,
  }
}

function applyTransition(state, transition, observedMs, incompleteReasons, path) {
  if (
    !transition ||
    (transition.to !== null && !STATUS_SET.has(transition.to)) ||
    (transition.from !== null && !STATUS_SET.has(transition.from))
  ) {
    incompleteReasons.add(`${path}:invalid_transition`)
    return
  }
  if (state.lastMs !== undefined && observedMs < state.lastMs) {
    incompleteReasons.add(`${path}:non_monotonic_timestamp`)
    return
  }
  if (state.currentStatus !== undefined && transition.from !== state.currentStatus) {
    incompleteReasons.add(`${path}:status_gap`)
    return
  }
  if (state.currentStatus !== undefined && state.lastMs !== undefined) {
    const bucket = WORKFLOW_DURATION_BUCKET[state.currentStatus]
    if (bucket) state[bucket] += observedMs - state.lastMs
  }
  if (!state.startedAt && ACTIVE.has(transition.to)) state.startedAt = new Date(observedMs).toISOString()
  if (transition.to === 'completed') {
    if (!state.firstCompletedAt) state.firstCompletedAt = new Date(observedMs).toISOString()
    state.lastCompletedAt = new Date(observedMs).toISOString()
  }
  if (ACTIVE.has(transition.to) && !ACTIVE.has(transition.from)) state.attemptCount++
  state.currentStatus = transition.to
  state.currentStatusSince = new Date(observedMs).toISOString()
  state.lastMs = observedMs
  state.transitionCount++
}

function closeOpenInterval(state, nowMs, incompleteReasons, path) {
  if (state.lastMs === undefined || !state.currentStatus) return
  if (nowMs < state.lastMs) {
    incompleteReasons.add(`${path}:now_before_last_transition`)
    return
  }
  const bucket = WORKFLOW_DURATION_BUCKET[state.currentStatus]
  if (bucket) state[bucket] += nowMs - state.lastMs
}

function summary(state, workflow = false) {
  const result = {
    ...(workflow
      ? {
          createdAt: state.createdAt,
          lastActivityAt: state.lastActivityAt,
          totalElapsedMs: state.createdAt ? Math.max(0, state.nowMs - Date.parse(state.createdAt)) : 0,
        }
      : {}),
    ...(state.startedAt ? { firstStartedAt: state.startedAt } : {}),
    ...(workflow && state.startedAt ? { startedAt: state.startedAt } : {}),
    ...(state.firstCompletedAt ? { firstCompletedAt: state.firstCompletedAt } : {}),
    ...(state.lastCompletedAt ? { lastCompletedAt: state.lastCompletedAt } : {}),
    ...(!workflow && state.currentStatusSince ? { lastTransitionAt: state.currentStatusSince } : {}),
    activeMs: state.activeMs,
    waitingHumanMs: state.waitingHumanMs,
    blockedMs: state.blockedMs,
    transitionCount: state.transitionCount,
    ...(!workflow ? { attemptCount: state.attemptCount } : {}),
    currentStatus: state.currentStatus ?? null,
    currentStatusSince: state.currentStatusSince ?? null,
  }
  return result
}

/** Project trusted Factory publication facts into duration summaries. Malformed history is skipped fail-closed. */
export function projectWorkflowTiming(facts, now, { snapshot } = {}) {
  const nowMs = now instanceof Date ? now.getTime() : Date.parse(now)
  if (!Number.isFinite(nowMs)) throw new TypeError('now must be a valid trusted instant')
  const reasons = new Set()
  const workflow = emptyState()
  const steps = new Map()
  for (let index = 0; index < (Array.isArray(facts) ? facts.length : 0); index++) {
    const fact = facts[index]
    if (!fact || !['projection_created', 'projection_published'].includes(fact.kind)) continue
    const observedMs = instant(fact.observedAt ?? fact.timestamp)
    if (observedMs === null || !fact.transitionDelta || typeof fact.transitionDelta !== 'object') {
      reasons.add(`fact:${index}:legacy_or_malformed`)
      continue
    }
    if (observedMs > nowMs) {
      reasons.add(`fact:${index}:future_timestamp`)
      continue
    }
    const observedAt = new Date(observedMs).toISOString()
    if (!workflow.createdAt && fact.kind === 'projection_created') workflow.createdAt = observedAt
    if (fact.transitionDelta.workflow)
      applyTransition(workflow, fact.transitionDelta.workflow, observedMs, reasons, `fact:${index}:workflow`)
    workflow.lastActivityAt = observedAt
    for (const change of Array.isArray(fact.transitionDelta.steps) ? fact.transitionDelta.steps : []) {
      if (!change || typeof change.stepId !== 'string') {
        reasons.add(`fact:${index}:invalid_step_change`)
        continue
      }
      const state = steps.get(change.stepId) ?? emptyState(change.stepId)
      applyTransition(state, change.status, observedMs, reasons, `fact:${index}:step:${change.stepId}`)
      steps.set(change.stepId, state)
    }
  }
  if (!workflow.createdAt) reasons.add('creation_time_unavailable')
  if (snapshot?.projection) {
    if (!workflow.currentStatus) workflow.currentStatus = snapshot.projection.status
    for (const step of snapshot.projection.steps ?? []) {
      const state = steps.get(step.id) ?? emptyState(step.id)
      if (!state.currentStatus) state.currentStatus = step.status
      steps.set(step.id, state)
    }
  }
  closeOpenInterval(workflow, nowMs, reasons, 'workflow')
  workflow.nowMs = nowMs
  for (const state of steps.values()) closeOpenInterval(state, nowMs, reasons, `step:${state.id}`)
  return {
    complete: reasons.size === 0,
    incompleteReasons: [...reasons],
    ...summary(workflow, true),
    steps: [...steps.values()]
      .map((state) => ({ stepId: state.id, ...summary(state) }))
      .sort((a, b) => a.stepId.localeCompare(b.stepId)),
  }
}
