/**
 * Factory Cockpit — projection step normalization.
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * This module used to carry the legacy Gantt rendering (the "ACTEUR · TEMPS"
 * block). That block duplicated the SSSF waterfall timeline of the run detail
 * view and has been removed; only the DTO normalization shared by the run
 * detail view survives here.
 *
 *   - workflow: `GET /api/factory/workflows/:id?namespaceId=…`
 *     → `{ projection: { steps: [{ id, name, status, responsibility, … }] } }`
 *   - timing:   `GET /api/factory/workflows/:id/timing?namespaceId=…`
 *     → `{ timing: { startedAt, createdAt, totalElapsedMs, steps: [{ stepId, … }] } }`
 *
 * Two adaptations matter:
 *
 *   1. Steps are keyed by their stable `id`, never by display name.
 *   2. Step status vocabulary is projected from v2 (`completed`, `failed`,
 *      `running`, `blocked`, …) onto the legible bar vocabulary while the raw
 *      status stays available as `projectionStatus`.
 */

import { esc, fmtDur, collectFlags, emptySuccess, EMPTY_SUCCESS_FLAG } from './facts.mjs'

export { esc, fmtDur, collectFlags, emptySuccess, EMPTY_SUCCESS_FLAG }

/** Projection v2 status → legible bar status. */
const STATUS_TO_BAR = Object.freeze({
  pass: 'pass',
  completed: 'pass',
  fail: 'fail',
  failed: 'fail',
  cancelled: 'fail',
  running: 'running',
  ready: 'running',
  waiting_human: 'blocked',
  blocked: 'blocked',
  pending: 'pending',
})

/** Map any projection v2 (or legacy) status onto a bar status. */
export function barStatus(status) {
  return STATUS_TO_BAR[status] ?? 'pending'
}

/** Coerce an instant (ISO string or epoch ms) to epoch ms, else `NaN`. */
export function toMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : Number.NaN
  }
  return Number.NaN
}

function finiteOr(value, fallback) {
  return Number.isFinite(value) ? value : fallback
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/** Sum the measured durations carried by a timing step. */
function timingDuration(timingStep) {
  if (!timingStep) return undefined
  const total =
    finiteOr(Number(timingStep.activeMs), 0) +
    finiteOr(Number(timingStep.waitingHumanMs), 0) +
    finiteOr(Number(timingStep.blockedMs), 0)
  return total
}

/**
 * Normalize the projection v2 workflow + timing DTOs into renderable steps.
 *
 * `facts`, `startedAt` and `durationMs` are read from the step when present
 * (enriched DTO), otherwise derived from the timing projection. Missing values
 * degrade explicitly — never fabricated.
 *
 * @param {any} workflow
 * @param {any} timing
 * @returns {Array<object>}
 */
export function normalizeSteps(workflow, timing) {
  const rawSteps = workflow?.projection?.steps ?? workflow?.steps ?? []
  if (!Array.isArray(rawSteps)) return []

  const timingById = new Map()
  for (const timingStep of timing?.steps ?? []) {
    if (timingStep && typeof timingStep.stepId === 'string') timingById.set(timingStep.stepId, timingStep)
  }

  return rawSteps.map((raw, index) => {
    if (!isPlainObject(raw)) {
      return {
        id: `step-${index + 1}`,
        name: `step-${index + 1}`,
        phaseKind: 'code',
        responsibility: null,
        description: null,
        facts: {},
        startedAt: null,
        durationMs: undefined,
        status: 'pending',
        projectionStatus: null,
        running: false,
        dependsOn: [],
        timing: null,
      }
    }
    const id = String(raw.id ?? raw.stepId ?? `step-${index + 1}`)
    const timingStep = timingById.get(id) ?? null
    const status = barStatus(raw.status)
    const facts = isPlainObject(raw.facts) ? raw.facts : {}
    return {
      id,
      name: String(raw.name ?? raw.title ?? raw.type ?? id),
      phaseKind: raw.lane ?? raw.phaseKind ?? raw.responsibility?.kind ?? raw.type ?? 'code',
      responsibility: isPlainObject(raw.responsibility) ? raw.responsibility : null,
      description: raw.description ?? null,
      facts,
      waitingQuestion: isPlainObject(raw.waitingQuestion)
        ? {
            questionRef: raw.waitingQuestion.questionRef == null ? null : String(raw.waitingQuestion.questionRef),
            text: raw.waitingQuestion.text == null ? '' : String(raw.waitingQuestion.text),
            type: raw.waitingQuestion.type == null ? 'FREE_TEXT' : String(raw.waitingQuestion.type),
            options: Array.isArray(raw.waitingQuestion.options)
              ? raw.waitingQuestion.options.map((option) => String(option))
              : [],
            attemptId: raw.waitingQuestion.attemptId == null ? null : String(raw.waitingQuestion.attemptId),
            caseId: raw.waitingQuestion.caseId == null ? null : String(raw.waitingQuestion.caseId),
          }
        : null,
      startedAt: raw.startedAt ?? timingStep?.firstStartedAt ?? timingStep?.currentStatusSince ?? null,
      durationMs: Number.isFinite(raw.durationMs) ? raw.durationMs : timingDuration(timingStep),
      status,
      projectionStatus: raw.status ?? null,
      running: status === 'running',
      dependsOn: Array.isArray(raw.dependsOn) ? raw.dependsOn : [],
      timing: timingStep,
    }
  })
}

export default {
  barStatus,
  toMs,
  normalizeSteps,
}
