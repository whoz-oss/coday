/**
 * Factory Cockpit — Gantt timeline algorithms & escaped rendering.
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * The algorithms are ported from the legacy run detail monolith
 * (`factory/dashboard/index.html`) and adapted to the Governed Projection v2
 * DTOs:
 *
 *   - workflow: `GET /api/factory/workflows/:id?namespaceId=…`
 *     → `{ projection: { steps: [{ id, name, status, responsibility, … }] } }`
 *   - timing:   `GET /api/factory/workflows/:id/timing?namespaceId=…`
 *     → `{ timing: { startedAt, createdAt, totalElapsedMs, steps: [{ stepId, … }] } }`
 *
 * Two adaptations matter:
 *
 *   1. Steps are keyed by their stable `id`, never by display name. The legacy
 *      code keyed every map by `phase.name`, which collides as soon as two
 *      lanes reuse a name.
 *   2. Step status vocabulary is projected from v2 (`completed`, `failed`,
 *      `running`, `blocked`, …) onto the legible bar vocabulary while the raw
 *      status stays available as `projectionStatus`.
 *
 * SECURITY: `renderBar` used to build a button with `innerHTML` and an inline
 * `onclick` string. This port returns strictly escaped markup instead and wires
 * interaction through `data-step-id` + event delegation. No dynamic value ever
 * reaches the output without {@link esc}.
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

/** Human labels for the state actually rendered on a bar. */
export const STATUS_LABELS = Object.freeze({
  pass: 'réussie',
  fail: 'échouée',
  running: 'en cours',
  blocked: 'bloquée',
  pending: 'en attente',
})

/** Bar accent colour, resolved against the Dockyard tokens. */
const STATUS_COLORS = Object.freeze({
  pass: 'var(--green)',
  fail: 'var(--red)',
  running: 'var(--blue)',
  blocked: 'var(--amber)',
  pending: 'var(--dim)',
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

function truncate(value, max) {
  const text = String(value ?? '')
  return text.length <= max ? text : `${text.slice(0, max)}…`
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
      phaseKind: raw.phaseKind ?? raw.responsibility?.kind ?? raw.type ?? 'code',
      responsibility: isPlainObject(raw.responsibility) ? raw.responsibility : null,
      description: raw.description ?? null,
      facts,
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

/**
 * Attribute a step to a swimlane.
 *
 * `code` (and `human`) steps belong to the orchestrator lane. `agent` steps go
 * into the lane of whoever actually answered — read from `agentsSelected` (the
 * observed fact), then the declared responsibility, never invented.
 *
 * @param {object} step
 * @returns {{ id: string, name: string, kind: string }}
 */
export function laneOf(step) {
  if (step?.phaseKind !== 'agent') return { id: '__orchestrator', name: 'orchestrateur', kind: 'code' }
  const facts = step.facts ?? {}
  const who =
    (facts.agentsSelected ?? [])[0] ??
    facts.agentName ??
    step.responsibility?.name ??
    (String(step.name ?? '').startsWith('analyse') ? 'analyste' : 'agent')
  return { id: String(who), name: String(who), kind: 'agent' }
}

/**
 * Compute the timeline origin and the total span, across steps and timing.
 *
 * @param {any} workflow
 * @param {Array<object>} steps
 * @param {any} timing
 * @param {number} [now]
 * @returns {{ t0: number, span: number }}
 */
export function timelineBounds(workflow, steps, timing, now = Date.now()) {
  const starts = steps.map((step) => toMs(step.startedAt)).filter(Number.isFinite)
  const originCandidates = [toMs(timing?.startedAt), toMs(timing?.createdAt), toMs(workflow?.startedAt)]
  const t0 = originCandidates.find(Number.isFinite) ?? (starts.length > 0 ? Math.min(...starts) : now)

  const totalElapsed = Number(timing?.totalElapsedMs)
  const lastActivity = toMs(timing?.lastActivityAt)
  const declaredDuration = Number(workflow?.durationMs)
  const fallbackEnd = Number.isFinite(totalElapsed)
    ? t0 + totalElapsed
    : Number.isFinite(lastActivity)
      ? lastActivity
      : t0
  const workflowEnd = Number.isFinite(declaredDuration) ? t0 + declaredDuration : fallbackEnd

  const stepEnd = steps.reduce((latest, step) => {
    const start = toMs(step.startedAt)
    if (!Number.isFinite(start)) return latest
    const duration = finiteOr(step.durationMs, step.running ? Math.max(now - start, 0) : 0)
    return Math.max(latest, start + duration)
  }, t0)

  return { t0, span: Math.max(stepEnd, workflowEnd) - t0 || 1000 }
}

/**
 * Build the warped global timeline: shared start coordinates plus anchors.
 *
 * Timestamps are the authority; a minimum gap (2.5% of the span) only widens
 * an already-chronological order so short steps stay clickable. The warped
 * coordinate map is used identically by bars and anchors — pixel distance is
 * therefore not claimed to be perfectly linear.
 *
 * @param {any} workflow
 * @param {Array<object>} steps
 * @param {any} timing
 * @param {number} [now]
 * @returns {{ t0: number, span: number, positions: Map<string, number>, anchors: Array<object> }}
 */
export function buildGlobalTimeline(workflow, steps, timing, now = Date.now()) {
  const { t0, span } = timelineBounds(workflow, steps, timing, now)
  const ordered = [...steps].sort(
    (a, b) => toMs(a.startedAt) - toMs(b.startedAt) || a.name.localeCompare(b.name) || a.id.localeCompare(b.id)
  )

  const minimumGapMs = span * 0.025
  const offsets = new Map()
  let previous = -Infinity
  for (const step of ordered) {
    const actual = toMs(step.startedAt) - t0
    const warped = Math.max(Number.isFinite(actual) ? actual : 0, previous + minimumGapMs)
    offsets.set(step.id, warped)
    previous = warped
  }

  const end = Math.max(span, previous)
  const positions = new Map([...offsets].map(([id, offset]) => [id, (offset / end) * 100]))
  const anchors = ordered.map((step) => ({
    stepId: step.id,
    pct: positions.get(step.id),
    label: fmtDur(toMs(step.startedAt) - t0),
  }))

  return { t0, span: end, positions, anchors }
}

/**
 * Non-colliding row layout for one lane.
 *
 * A step keeps exactly its chronological start. A minimum width never moves a
 * bar: if it cannot fit before the next one, the short step becomes a compact
 * marker. Remaining collisions go to deterministic sub-rows (start, end, name).
 *
 * @param {Array<object>} steps
 * @param {ReturnType<typeof buildGlobalTimeline>} timeline
 * @param {any} workflow
 * @param {number} [now]
 * @returns {{ items: Array<object>, height: number }}
 */
export function layoutLaneBars(steps, timeline, workflow, now = Date.now()) {
  const minWidth = 2.1
  const gap = 0.4

  const sorted = steps
    .map((step) => {
      const start = toMs(step.startedAt)
      const duration = finiteOr(step.durationMs, step.running ? Math.max(now - start, 0) : 0)
      const left = finiteOr(timeline.positions.get(step.id), 0)
      return {
        step,
        start,
        end: finiteOr(start, 0) + duration,
        left,
        naturalWidth: (duration / timeline.span) * 100,
      }
    })
    .sort(
      (a, b) =>
        a.start - b.start ||
        a.end - b.end ||
        a.step.name.localeCompare(b.step.name) ||
        a.step.id.localeCompare(b.step.id)
    )

  const rows = []
  const items = []
  for (const item of sorted) {
    // Keep the whole chip inside the plot. At the far edge its readable width
    // may shrink, but its shared global start coordinate never moves.
    const width = Math.max(0.5, Math.min(Math.max(item.naturalWidth, minWidth), 100 - item.left))
    const compact = item.naturalWidth < minWidth || width < minWidth
    let row = rows.findIndex((state) => item.start >= state.actualEnd && item.left >= state.visualRight + gap)
    if (row === -1) {
      row = rows.length
      rows.push({ actualEnd: -Infinity, visualRight: -Infinity })
    }
    rows[row].actualEnd = item.end
    rows[row].visualRight = item.left + width
    items.push({ step: item.step, left: item.left, width, compact, top: 12 + row * 38, row })
  }

  return { items, height: Math.max(56, 24 + rows.length * 38) }
}

/**
 * Generate evenly spaced time-axis ticks for a span (ms).
 *
 * @param {number} span
 * @returns {Array<{ pct: number, label: string }>}
 */
export function buildTicks(span) {
  const safeSpan = Number.isFinite(span) && span > 0 ? span : 1
  const targets = [1e3, 2e3, 5e3, 1e4, 15e3, 3e4, 6e4, 12e4, 3e5, 6e5, 12e5, 18e5]
  const step = targets.find((target) => safeSpan / target <= 10) ?? Math.ceil(safeSpan / 8)
  const out = []
  for (let elapsed = 0; elapsed <= safeSpan; elapsed += step) {
    out.push({ pct: (elapsed / safeSpan) * 100, label: fmtDur(elapsed) })
  }
  return out
}

/**
 * Lane subtitle: what we actually know, never an invented agent model.
 *
 * @param {{ id: string } | null | undefined} lane
 * @param {Array<object>} [steps]
 * @param {any} [workflow]
 * @returns {string}
 */
export function laneSubtitle(lane, steps = [], workflow = null) {
  if (!lane) return ''
  if (lane.id === '__orchestrator') {
    const scope = workflow?.context?.command ?? workflow?.projection?.workflowType ?? 'gates + oracle'
    return truncate(scope, 34)
  }
  const count = steps.length
  return `${count} étape${count > 1 ? 's' : ''}`
}

/**
 * Render one step bar as strictly escaped markup.
 *
 * @param {object} step
 * @param {{ left: number, width: number, top: number, compact?: boolean }} layout
 * @param {string|null} [selectedStepId]
 * @returns {string}
 */
export function renderBar(step, layout, selectedStepId = null) {
  const left = Number(layout?.left ?? 0)
  const width = Number(layout?.width ?? 0)
  const top = Number(layout?.top ?? 0)
  const compact = Boolean(layout?.compact)
  const status = barStatus(step?.status)
  const stateLabel = STATUS_LABELS[status] ?? status

  const flags = collectFlags(step)
  if (emptySuccess(step)) flags.push(EMPTY_SUCCESS_FLAG)

  const durationLabel = step?.durationMs ? fmtDur(step.durationMs) : 'en cours'
  const tip =
    `${step?.name ?? ''} — ${durationLabel}` +
    (flags.length > 0 ? `\n${flags.map((flag) => `${flag.icon} ${flag.label}`).join('\n')}` : '')

  const selected = selectedStepId === step?.id
  const wide = width > 9 || compact
  const classes = ['bar', status, compact ? 'compact' : '', selected ? 'selected' : ''].filter(Boolean).join(' ')
  const style =
    `position:absolute;left:${left.toFixed(3)}%;width:${width.toFixed(3)}%;top:${top}px;height:26px;` +
    `display:flex;align-items:center;gap:4px;overflow:hidden;border-radius:6px;border:1px solid ${STATUS_COLORS[status] ?? 'var(--border)'};` +
    `background:var(--panel-2);color:var(--text);cursor:pointer;padding:0 6px;font:inherit;text-align:left`

  const flagSpans = flags.map((flag) => `<span class="bar-flag" aria-hidden="true">${esc(flag.icon)}</span>`).join('')
  const nameSpan = wide
    ? `<span class="bar-name" style="font-size:11px;white-space:nowrap;overflow:hidden;` +
      `text-overflow:ellipsis">${esc(step?.name ?? '')}</span>`
    : ''
  const durSpan =
    wide && step?.durationMs
      ? `<span class="bar-dur" style="font-size:10px;color:var(--faint);margin-left:auto;` +
        `white-space:nowrap">${esc(fmtDur(step.durationMs))}</span>`
      : ''

  return (
    `<button type="button" class="${classes}" style="${style}"` +
    ` data-step-id="${esc(step?.id ?? '')}"` +
    ` title="${esc(tip)}"` +
    ` aria-label="${esc(`Étape ${step?.name ?? ''}, ${stateLabel}, ${durationLabel}`)}"` +
    ` aria-pressed="${selected}">` +
    `<span class="sr-only">${esc(`${stateLabel} : `)}</span>` +
    nameSpan +
    flagSpans +
    durSpan +
    '</button>'
  )
}

/**
 * Render the full Gantt (axis + lanes) as a strictly escaped markup string.
 *
 * @param {{ workflow?: any, timing?: any, steps?: Array<object>, selectedStepId?: string|null, now?: number }} [options]
 * @returns {string}
 */
export function renderGantt(options = {}) {
  const { workflow = null, timing = null, selectedStepId = null, now = Date.now() } = options
  const steps = options.steps ?? normalizeSteps(workflow, timing)
  const timed = steps.filter((step) => Number.isFinite(toMs(step.startedAt)))

  if (timed.length === 0) {
    return (
      '<div class="panel"><p class="placeholder" data-gantt-empty="true">' +
      'Aucune étape horodatée dans cette projection.</p></div>'
    )
  }

  const timeline = buildGlobalTimeline(workflow, timed, timing, now)

  const lanes = []
  const laneIndex = new Map()
  for (const step of timed) {
    const lane = laneOf(step)
    if (!laneIndex.has(lane.id)) {
      laneIndex.set(lane.id, lanes.length)
      lanes.push({ ...lane, steps: [] })
    }
    lanes[laneIndex.get(lane.id)].steps.push(step)
  }
  lanes.sort((a, b) => (a.id === '__orchestrator' ? -1 : b.id === '__orchestrator' ? 1 : 0))

  const gridLine = 'position:absolute;top:0;bottom:0;width:1px;background:var(--border-soft)'
  const grid = timeline.anchors
    .map((anchor) => `<span class="gantt-grid-line" style="${gridLine};left:${anchor.pct}%"></span>`)
    .join('')
  const axis = buildTicks(timeline.span)
    .map(
      (tick) =>
        `<span class="axis-tick" style="position:absolute;top:0;transform:translateX(-50%);` +
        `font-size:10px;color:var(--faint);white-space:nowrap;left:${tick.pct}%">${esc(tick.label)}</span>`
    )
    .join('')

  const axisHtml =
    '<div class="gantt-axis" style="display:flex;align-items:flex-end;gap:8px;margin-bottom:6px">' +
    '<div class="axis-corner" style="min-width:180px;font-size:10px;text-transform:uppercase;' +
    'letter-spacing:.06em;color:var(--faint)">acteur · temps</div>' +
    `<div class="axis-track" style="position:relative;flex:1;height:20px;border-bottom:1px solid var(--border)">${axis}</div>` +
    '</div>'

  const lanesHtml = lanes
    .map((lane) => {
      const subtitle = laneSubtitle(lane, lane.steps, workflow)
      const layout = layoutLaneBars(lane.steps, timeline, workflow, now)
      const bars = layout.items.map((item) => renderBar(item.step, item, selectedStepId)).join('')
      const laneName = lane.id === '__orchestrator' ? 'orchestrateur' : lane.name
      return (
        `<div class="lane" style="display:flex;gap:8px;min-height:${layout.height}px;margin-bottom:6px">` +
        '<div class="lane-label" style="min-width:180px;max-width:180px">' +
        `<div class="nm" style="font-size:12px;color:var(--text)">${esc(laneName)}</div>` +
        `<div class="sub" title="${esc(subtitle)}" style="font-size:10px;color:var(--faint)">${esc(subtitle)}</div>` +
        '</div>' +
        `<div class="lane-track" style="position:relative;flex:1;min-height:${layout.height}px;` +
        'background:var(--panel-3);border:1px solid var(--border-soft);border-radius:8px">' +
        `<div class="lane-grid" style="position:absolute;inset:0">${grid}</div>` +
        bars +
        '</div></div>'
      )
    })
    .join('')

  return `<div class="gantt" data-gantt="true"><div class="gantt-inner">${axisHtml}${lanesHtml}</div></div>`
}

export default {
  barStatus,
  toMs,
  normalizeSteps,
  laneOf,
  timelineBounds,
  buildGlobalTimeline,
  layoutLaneBars,
  buildTicks,
  laneSubtitle,
  renderBar,
  renderGantt,
}
