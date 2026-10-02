/**
 * Factory Cockpit — temporal lanes component.
 *
 * Vanilla ESM, zero dependencies, zero build step. Two renderings live here:
 *
 *   1. the compact inline swimlanes (`buildBlueprintLayout` + `renderTemporalLanes`),
 *      a public helper/tested contract kept for backwards compatibility;
 *   2. the SSSF-style horizontal waterfall used by the run detail view
 *      (`buildWaterfallLayout` + `renderWaterfallTimeline`): a run-strip band
 *      followed by one lane per actor, with phase blocks positioned in REAL
 *      TIME from `startedAt` / `durationMs` (never `1/total`).
 *
 * The module is purely computational: the `build*` functions return structured
 * layouts, the `render*` functions serialize them to escaped HTML strings.
 * Nothing touches `window` or `document`, so it is import-safe in Node.
 *
 * MISSING-DATA RULE — any metric the Factory projection does not carry (cost,
 * tokens, read, written, agent model, context %) renders the literal `-` (or an
 * empty bar for the context), never silence. The structure is complete today and
 * ready to receive real values later.
 */

import { esc, fmtDur } from './facts.mjs'

export { esc, fmtDur }

export const ACTOR_KINDS = Object.freeze(['human', 'agent', 'code'])

export const STEP_STATES = Object.freeze(['completed', 'active', 'pending', 'failed'])

export const LANE_LABELS = Object.freeze({ human: 'Humain', agent: 'Agent', code: 'Code' })

/** Literal placeholder for any projection value the Factory does not provide. */
export const DASH = '-'

/** Actor-kind accent colours, aligned with the Dockyard tokens. */
export const LANE_COLORS = Object.freeze({
  human: 'var(--amber)',
  code: 'var(--cyan)',
})

/** Palette cycled across distinct agent lanes. */
export const AGENT_COLORS = Object.freeze([
  'var(--purple)',
  'var(--violet)',
  'var(--blue)',
  'var(--green)',
  'var(--amber)',
  'var(--cyan)',
])

/** Lane glyphs: engineer = person, code = terminal, agent = robot. */
export const LANE_ICONS = Object.freeze({ human: '\u{1F464}', code: '\u{1F4BB}', agent: '\u{1F916}' })

/** Status glyphs: completed, failed, active, pending. */
export const STATE_GLYPHS = Object.freeze({
  completed: '\u2713',
  failed: '\u2717',
  active: '\u25CF',
  pending: '\u25CB',
})

// Fallback heuristics used only when a step carries no explicit responsibility.
const CODE_HINTS = /(oracle|build|test|lint|scan|typecheck|type-check|compile|deterministic|verify|code)/i
const HUMAN_HINTS = /(human|humain|approv|validat|decision|manual|confirm|review|gate|revue)/i

const TERMINAL_STEP_STATUSES = new Set(['completed', 'failed', 'cancelled', 'succeeded'])

/** Normalize wire statuses before lifecycle comparisons. */
function normalizedStatus(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

/** A started step remains temporal until an authoritative end/final duration exists. */
export function isTemporalStep(step) {
  if (toEpochMs(step?.startedAt) === null) return false
  if (toEpochMs(step?.completedAt ?? step?.endedAt) !== null) return false
  if (Number.isFinite(step?.durationMs)) return false
  return !TERMINAL_STEP_STATUSES.has(normalizedStatus(step?.status))
}

/** Escape a value for safe HTML text interpolation. */
export function escapeHtml(value) {
  return esc(value)
}

/** Coerce an instant (ISO string or epoch ms) to epoch ms, else `null`. */
export function toEpochMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** Round to a fixed number of decimals, dropping float noise. */
function round(value, decimals = 4) {
  const factor = 10 ** decimals
  return Math.round(value * factor) / factor
}

/**
 * Resolve the actor kind of a step: an explicit projection `lane` first, then
 * the declared responsibility `kind`, otherwise a deterministic hint-based
 * fallback defaulting to `agent`.
 *
 * @param {{ lane?: string, responsibility?: { kind?: string }, id?: string, name?: string }} step
 * @returns {'human'|'agent'|'code'}
 */
export function classifyActorKind(step) {
  const lane = step?.lane
  if (lane === 'human' || lane === 'agent' || lane === 'code') return lane
  const kind = step?.responsibility?.kind
  if (kind === 'human' || kind === 'agent' || kind === 'code') return kind
  const haystack = `${step?.id ?? ''} ${step?.name ?? ''}`
  if (CODE_HINTS.test(haystack)) return 'code'
  if (HUMAN_HINTS.test(haystack)) return 'human'
  return 'agent'
}

/**
 * Resolve the display name of the actor behind a step. Agent steps are grouped
 * into one lane per distinct name.
 *
 * @param {any} step
 * @returns {string}
 */
export function actorName(step) {
  const name = step?.responsibility?.name ?? step?.agentName ?? step?.facts?.agentName ?? null
  return typeof name === 'string' && name.trim() ? name.trim() : 'agent'
}

/**
 * Classify a step into a concrete lane descriptor.
 *
 *   - human  → the single `engineer` lane (amber);
 *   - code   → the single `code` lane (cyan);
 *   - agent  → one lane per distinct agent name.
 *
 * @param {any} step
 * @returns {{ id: string, kind: 'human'|'agent'|'code', label: string }}
 */
export function classifyLane(step) {
  const kind = classifyActorKind(step)
  if (kind === 'human') return { id: 'engineer', kind: 'human', label: 'engineer' }
  if (kind === 'code') return { id: 'code', kind: 'code', label: 'code' }
  const name = actorName(step)
  return { id: `agent:${name}`, kind: 'agent', label: name }
}

/**
 * Map a projection step status (and the optional active step id) to one of the
 * four visual states.
 *
 * @param {{ id?: string, status?: string }} step
 * @param {string|null} [activeStepId]
 * @returns {'completed'|'active'|'pending'|'failed'}
 */
export function resolveStepState(step, activeStepId) {
  const status = normalizedStatus(step?.status) || 'pending'
  const terminal = TERMINAL_STEP_STATUSES.has(status)
  if (activeStepId && step?.id === activeStepId && !terminal) return 'active'
  switch (status) {
    case 'completed':
    case 'cancelled':
      return 'completed'
    case 'failed':
      return 'failed'
    case 'running':
    case 'waiting_human':
      return 'active'
    default:
      return 'pending'
  }
}

/** Duration of a step in ms, or `null` when the projection does not provide it. */
export function stepDurationMs(step, now = Date.now(), state = resolveStepState(step, null)) {
  const start = toEpochMs(step?.startedAt)
  const end = toEpochMs(step?.completedAt ?? step?.endedAt)
  if (start !== null && end !== null) return Math.max(end - start, 0)
  // Active blocks are elapsed/runtime displays, not completion percentages.
  // Their authoritative start is extended only to the caller's local clock.
  if (state === 'active' && start !== null && Number.isFinite(now)) return Math.max(now - start, 0)
  if (Number.isFinite(step?.durationMs)) return Number(step.durationMs)
  return null
}

/**
 * Wrap any projection status into a chip class.
 *
 * @param {string} status
 * @returns {string}
 */
export function statusChipClass(status) {
  if (status === 'completed' || status === 'pass') return 'chip chip-success'
  if (status === 'failed' || status === 'fail' || status === 'cancelled') return 'chip chip-fail'
  if (status === 'waiting_human' || status === 'blocked') return 'chip chip-wave'
  if (status === 'running' || status === 'ready') return 'chip chip-running'
  return 'chip'
}

// ---------------------------------------------------------------------------
// Compact inline swimlanes (workflow cards) — unchanged public behaviour.
// ---------------------------------------------------------------------------

/**
 * Flatten a mixed list of phases and/or steps into a single ordered step list.
 * An item carrying an array `steps` is treated as a phase and unwrapped.
 *
 * @param {any} phases
 * @returns {any[]}
 */
function normalizeSteps(phases) {
  if (!phases) return []
  const list = Array.isArray(phases) ? phases : Array.isArray(phases.steps) ? [phases] : []
  const steps = []
  for (const item of list) {
    if (!item || typeof item !== 'object') continue
    if (Array.isArray(item.steps)) {
      for (const step of item.steps) if (step && typeof step === 'object') steps.push(step)
    } else {
      steps.push(item)
    }
  }
  return steps
}

/**
 * Build the compact temporal lane layout for a workflow blueprint.
 *
 * @param {Array<any>|{ steps?: any[] }} phases steps or phases-with-steps
 * @param {string} [activeStepId] explicit active step id (overrides running detection)
 * @returns {{
 *   lanes: { human: any[], agent: any[], code: any[] },
 *   steps: any[],
 *   summary: { totalSteps: number, activeStepId: (string|null), completionRate: number,
 *     laneCounts: { human: number, agent: number, code: number } }
 * }}
 */
export function buildBlueprintLayout(phases, activeStepId) {
  const steps = normalizeSteps(phases)
  let resolvedActive = typeof activeStepId === 'string' && activeStepId ? activeStepId : null
  if (!resolvedActive) {
    const candidate = steps.find((step) => step?.status === 'running' || step?.status === 'waiting_human')
    resolvedActive = candidate?.id ?? null
  }

  const lanes = { human: [], agent: [], code: [] }
  const flat = []
  const total = steps.length || 1

  steps.forEach((step, index) => {
    const actorKind = classifyActorKind(step)
    const state = resolveStepState(step, resolvedActive)
    const lane = lanes[actorKind]
    const laneIndex = lane.length
    const timing = {
      index,
      laneIndex,
      startRatio: index / total,
      widthRatio: 1 / total,
      durationMs: Number.isFinite(step?.durationMs) ? step.durationMs : null,
      startedAt: typeof step?.startedAt === 'string' ? step.startedAt : null,
      endedAt:
        typeof step?.endedAt === 'string'
          ? step.endedAt
          : typeof step?.completedAt === 'string'
            ? step.completedAt
            : null,
    }
    const node = {
      id: step?.id ?? `step-${index}`,
      name: step?.name ?? step?.id ?? `step-${index}`,
      status: step?.status ?? 'pending',
      state,
      actorKind,
      lane: actorKind,
      responsibility: step?.responsibility ?? null,
      phase: step?.phase ?? null,
      dependsOn: Array.isArray(step?.dependsOn) ? [...step.dependsOn] : [],
      timing,
    }
    lane.push(node)
    flat.push(node)
  })

  const completed = flat.filter((node) => node.state === 'completed').length
  const summary = {
    totalSteps: flat.length,
    activeStepId: resolvedActive,
    completionRate: flat.length ? completed / flat.length : 0,
    laneCounts: {
      human: lanes.human.length,
      agent: lanes.agent.length,
      code: lanes.code.length,
    },
  }

  return { lanes, steps: flat, summary }
}

/**
 * Render the three compact temporal swimlanes as an HTML string.
 *
 * @param {ReturnType<typeof buildBlueprintLayout>} layout
 * @param {{ compact?: boolean }} [options]
 * @returns {string}
 */
export function renderTemporalLanes(layout, options = {}) {
  const lanes = layout?.lanes ?? { human: [], agent: [], code: [] }
  const compactClass = options.compact ? ' temporal-lanes-compact' : ''
  const body = ACTOR_KINDS.map((kind) => {
    const nodes = Array.isArray(lanes[kind]) ? lanes[kind] : []
    const steps = nodes.length
      ? nodes
          .map((node) => {
            const style = `--start:${node.timing?.startRatio ?? 0};--width:${node.timing?.widthRatio ?? 1}`
            const actor = node.responsibility?.name ?? null
            const title = actor ? `${node.name} — ${actor} (${node.state})` : `${node.name} (${node.state})`
            return (
              `<li class="lane-step state-${esc(node.state)}" data-step-id="${esc(node.id)}" ` +
              `data-lane="${kind}" data-status="${esc(node.status)}" ` +
              `data-state="${esc(node.state)}" style="${style}" title="${esc(title)}">` +
              `<span class="lane-step-name">${esc(node.name)}</span>` +
              (actor ? `<span class="lane-step-actor" data-actor="${esc(actor)}">${esc(actor)}</span>` : '') +
              `<span class="lane-step-status" data-status-label="${esc(node.state)}">${esc(node.state)}</span>` +
              `</li>`
            )
          })
          .join('')
      : '<li class="lane-step lane-empty" aria-hidden="true">—</li>'
    return (
      `<div class="lane lane-${kind}" data-lane="${kind}">` +
      `<div class="lane-title">${esc(LANE_LABELS[kind])}</div>` +
      `<ol class="lane-steps">${steps}</ol>` +
      `</div>`
    )
  }).join('')

  return `<div class="temporal-lanes${compactClass}">${body}</div>`
}

// ---------------------------------------------------------------------------
// SSSF-style horizontal waterfall (run detail).
// ---------------------------------------------------------------------------

/** Synthetic non-governed timeline identity for the persisted engineer request. */
export const CONTROLLER_REQUEST_ACTIVITY_ID = 'controller-request'

/** Normalize the flexible waterfall input into `{ steps, meta, controllerRequest }`. */
function extractInput(input, options = {}) {
  if (Array.isArray(input)) {
    return {
      steps: input,
      meta: {
        title: options.title ?? options.workflowId ?? null,
        status: options.status ?? null,
        workflowType: options.workflowType ?? null,
        startedAt: options.startedAt ?? null,
      },
      controllerRequest: options.controllerRequest ?? null,
    }
  }
  const projection = input?.projection && typeof input.projection === 'object' ? input.projection : input
  const steps = Array.isArray(projection?.steps) ? projection.steps : []
  return {
    steps,
    meta: {
      title: options.title ?? projection?.title ?? input?.title ?? options.workflowId ?? null,
      status: options.status ?? projection?.status ?? input?.status ?? null,
      workflowType: options.workflowType ?? projection?.workflowType ?? input?.workflowType ?? null,
      startedAt: options.startedAt ?? projection?.startedAt ?? input?.startedAt ?? null,
    },
    controllerRequest: options.controllerRequest ?? input?.controllerRequest ?? projection?.controllerRequest ?? input?.instance?.controllerRequest ?? null,
  }
}

const TICK_STEPS_MS = Object.freeze([
  1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000, 900000, 1800000, 3600000, 7200000, 14400000,
])

/** Axis label for a relative millisecond offset (`0s`, `30s`, `1m`, `2.5m`). */
export function axisLabel(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return '0s'
  if (ms < 60000) return `${Math.round(ms / 1000)}s`
  const minutes = ms / 60000
  return Number.isInteger(minutes) ? `${minutes}m` : `${minutes.toFixed(1)}m`
}

/**
 * Build evenly spaced axis ticks (0s … span) painted across the track.
 *
 * @param {number} spanMs
 * @param {number} [target=5]
 * @returns {Array<{ pct: number, label: string }>}
 */
export function buildTicks(spanMs, target = 5) {
  const span = Number.isFinite(spanMs) && spanMs > 0 ? spanMs : 1
  const rawStep = span / target
  const stepMs = TICK_STEPS_MS.find((candidate) => candidate >= rawStep) ?? TICK_STEPS_MS[TICK_STEPS_MS.length - 1]
  const ticks = []
  for (let offset = 0; offset < span; offset += stepMs) {
    ticks.push({ pct: round((offset / span) * 100, 3), label: axisLabel(offset) })
  }
  ticks.push({ pct: 100, label: axisLabel(span) })
  return ticks
}

/**
 * Lay out steps on a real-time horizontal axis.
 *
 * Each block's `leftPct` derives from `(startedAt - t0) / span`; `widthPct`
 * from `durationMs / span`. A minimum width floor keeps very short phases (a
 * commit, say) legible, and subsequent blocks are shifted right so sequential
 * phases never overlap. The whole strip is finally normalized to fit 100%.
 * Steps without a `startedAt` (pending) are queued at the right in dashed
 * blocks.
 *
 * @param {Array<any>|{ steps?: any[], projection?: any }} input projection, workflow or raw step list
 * @param {{ now?: number, startedAt?: string, activeStepId?: string|null, minBlockPct?: number,
 *   title?: string, status?: string, workflowType?: string, workflowId?: string }} [options]
 * @returns {object} structured waterfall layout
 */
export function buildWaterfallLayout(input, options = {}) {
  const { steps: rawSteps, meta, controllerRequest } = extractInput(input, options)
  const now = Number.isFinite(options.now) ? options.now : Date.now()
  const requestStart = toEpochMs(controllerRequest?.observedAt)
  const firstStepStart = rawSteps.map((step) => toEpochMs(step?.startedAt)).filter((value) => value !== null).sort((a, b) => a - b)[0] ?? null
  const controllerActivity = controllerRequest && requestStart !== null
    ? {
        id: CONTROLLER_REQUEST_ACTIVITY_ID,
        name: 'Demande ingénieur',
        lane: 'human',
        responsibility: { kind: 'human', name: controllerRequest.actorId ?? 'engineer' },
        status: 'completed',
        startedAt: controllerRequest.observedAt,
        durationMs: firstStepStart !== null && firstStepStart >= requestStart ? firstStepStart - requestStart : 0,
        nonGoverned: true,
        controllerRequest,
      }
    : null
  const timelineSteps = controllerActivity ? [controllerActivity, ...rawSteps] : rawSteps
  const minBlockPct = Number.isFinite(options.minBlockPct) ? Math.max(Number(options.minBlockPct), 0.5) : 3

  const enriched = timelineSteps.map((step, index) => {
    const start = toEpochMs(step?.startedAt)
    const state = resolveStepState(step, options.activeStepId ?? null)
    const duration = stepDurationMs(step, now, state)
    const lane = classifyLane(step)
    return {
      raw: step,
      index,
      id: String(step?.id ?? `step-${index + 1}`),
      name: String(step?.name ?? step?.title ?? step?.id ?? `step-${index + 1}`),
      status: step?.status ?? 'pending',
      state,
      lane,
      start,
      duration,
      pending: start === null,
      nonGoverned: step?.nonGoverned === true,
      controllerRequest: step?.controllerRequest ?? null,
    }
  })

  const starts = enriched.map((entry) => entry.start).filter((value) => value !== null)
  const metaStart = toEpochMs(meta.startedAt)
  const t0 = starts.length ? Math.min(...starts) : (metaStart ?? now)
  const ends = enriched.filter((entry) => entry.start !== null).map((entry) => entry.start + (entry.duration ?? 0))
  const measuredEnd = ends.length ? Math.max(...ends) : t0
  // A workflow-level status can lag or be terminal while a projected step still
  // has an authoritative open interval. Step chronology therefore owns ticking.
  const running = rawSteps.some(isTemporalStep)
  // Keep the open block, runtime strip and axis on the same local-now bound.
  const tEnd = running ? Math.max(measuredEnd, now) : measuredEnd
  const span = Math.max(tEnd - t0, 1)

  const timed = enriched.filter((entry) => entry.start !== null).sort((a, b) => a.start - b.start || a.index - b.index)
  const queued = enriched.filter((entry) => entry.start === null)

  let cursor = 0
  const placed = []
  for (const entry of [...timed, ...queued]) {
    let left = entry.start !== null ? ((entry.start - t0) / span) * 100 : cursor
    let width = entry.duration !== null ? (entry.duration / span) * 100 : minBlockPct
    width = Math.max(width, minBlockPct)
    left = Math.max(left, cursor)
    cursor = left + width
    placed.push({ ...entry, leftPct: left, widthPct: width })
  }

  const scale = cursor > 100 ? 100 / cursor : 1

  // Group into lanes: engineer (always), code (if any), then one lane per agent.
  const order = []
  const byId = new Map()
  const ensureLane = (descriptor) => {
    if (!byId.has(descriptor.id)) {
      const lane = {
        id: descriptor.id,
        kind: descriptor.kind,
        label: descriptor.label,
        icon: LANE_ICONS[descriptor.kind],
        color: null,
        blocks: [],
      }
      byId.set(descriptor.id, lane)
      order.push(lane)
    }
    return byId.get(descriptor.id)
  }
  ensureLane({ id: 'engineer', kind: 'human', label: 'engineer' })

  for (const entry of placed) {
    const lane = ensureLane(entry.lane)
    lane.blocks.push({
      id: entry.id,
      name: entry.name,
      status: entry.status,
      state: entry.state,
      glyph: STATE_GLYPHS[entry.state] ?? STATE_GLYPHS.pending,
      durationMs: entry.duration,
      durationLabel: entry.duration !== null ? fmtDur(entry.duration) : DASH,
      pending: entry.pending,
      laneId: entry.lane.id,
      nonGoverned: entry.nonGoverned,
      controllerRequest: entry.controllerRequest,
      leftPct: round(entry.leftPct * scale, 4),
      widthPct: round(entry.widthPct * scale, 4),
    })
  }

  // Agent lanes render model + context placeholders; every agent gets a colour.
  // Lane order is fixed: engineer, code, then agents in first-appearance order.
  const ordered = [
    ...order.filter((lane) => lane.kind === 'human'),
    ...order.filter((lane) => lane.kind === 'code'),
    ...order.filter((lane) => lane.kind === 'agent'),
  ]
  let agentIndex = 0
  for (const lane of ordered) {
    if (lane.kind === 'agent') {
      lane.color = AGENT_COLORS[agentIndex % AGENT_COLORS.length]
      lane.model = DASH
      lane.contextPct = null
      agentIndex += 1
    } else {
      lane.color = LANE_COLORS[lane.kind]
    }
  }

  const runtimeEnd = running ? Math.max(now, t0) : tEnd
  const runtimeMs = runtimeEnd >= t0 ? runtimeEnd - t0 : 0

  const strip = {
    title: meta.title ?? DASH,
    status: meta.status ?? 'unknown',
    statusClass: statusChipClass(meta.status),
    startedLabel: t0 !== null ? new Date(t0).toISOString() : DASH,
    workflowType: meta.workflowType ?? DASH,
    stats: [
      { key: 'cost', label: 'COST', icon: '$', value: DASH, available: false },
      { key: 'runtime', label: 'RUNTIME', icon: '\u23F1', value: fmtDur(runtimeMs), available: true },
      { key: 'tokens', label: 'TOKENS', icon: '\u25C6', value: DASH, available: false },
      { key: 'read', label: 'READ', icon: '\u2193', value: DASH, available: false },
      { key: 'written', label: 'WRITTEN', icon: '\u2191', value: DASH, available: false },
    ],
  }

  return {
    waterfall: true,
    strip,
    axis: { ticks: buildTicks(span) },
    lanes: ordered,
    bounds: { t0, tEnd, span, runtimeMs, running },
    controllerRequest,
    summary: {
      totalSteps: rawSteps.length,
      laneCount: ordered.length,
      completed: placed.filter((entry) => entry.state === 'completed').length,
      failed: placed.filter((entry) => entry.state === 'failed').length,
    },
  }
}

/**
 * Render the run-strip band: title, status chip, start date, run type and the
 * statistics chips (COST / RUNTIME / TOKENS / READ / WRITTEN).
 *
 * @param {ReturnType<typeof buildWaterfallLayout>['strip']} strip
 * @returns {string}
 */
export function renderRunStrip(strip) {
  if (!strip) return ''
  const stats = strip.stats
    .map(
      (stat) =>
        `<span class="stat-chip${stat.available ? '' : ' stat-missing'}" data-stat="${esc(stat.key)}">` +
        `<span class="stat-ico" aria-hidden="true">${esc(stat.icon)}</span>` +
        `<span class="stat-key">${esc(stat.label)}</span>` +
        `<span class="stat-val mono">${esc(stat.value)}</span>` +
        `</span>`
    )
    .join('')
  return (
    '<div class="run-strip" data-run-strip="true">' +
    '<div class="run-strip-main">' +
    `<span class="run-strip-title" title="${esc(strip.title)}">${esc(strip.title)}</span>` +
    `<span class="${strip.statusClass} run-strip-status" data-run-status="${esc(strip.status)}">${esc(
      strip.status
    )}</span>` +
    `<span class="run-strip-meta">started ${esc(strip.startedLabel)}</span>` +
    `<span class="chip run-strip-type">${esc(strip.workflowType)}</span>` +
    '</div>' +
    `<div class="run-strip-stats">${stats}</div>` +
    '</div>'
  )
}

/** Render one phase block positioned along a lane track. */
function renderBlock(block) {
  const title = `${block.name} (${block.state}) — ${block.durationLabel}`
  const queued = block.pending ? '<span class="block-queued">queued</span>' : ''
  return (
    `<div class="waterfall-block state-${esc(block.state)}${block.pending ? ' is-queued' : ''}${block.nonGoverned ? ' is-non-governed' : ''}" ` +
    `data-step-id="${esc(block.id)}" data-lane="${esc(block.laneId)}" ` +
    `data-status="${esc(block.status)}" data-state="${esc(block.state)}" data-non-governed="${block.nonGoverned ? 'true' : 'false'}" ` +
    `style="left:${block.leftPct}%;width:${block.widthPct}%" title="${esc(title)}">` +
    `<span class="block-glyph" aria-hidden="true">${esc(block.glyph)}</span>` +
    `<span class="block-name">${esc(block.name)}</span>` +
    `<span class="block-duration mono">${esc(block.durationLabel)}</span>` +
    queued +
    '</div>'
  )
}

/** Render the agent-only model / context sub-meta (both placeholders today). */
function renderLaneSubmeta(lane) {
  const contextPct = Number.isFinite(lane.contextPct) ? lane.contextPct : 0
  const contextValue = Number.isFinite(lane.contextPct) ? `${lane.contextPct}%` : DASH
  return (
    '<div class="lane-submeta">' +
    '<span class="submeta-row"><span class="submeta-ico" aria-hidden="true">\u{1F9E0}</span>' +
    `<span class="submeta-key">Model</span><span class="submeta-val mono">${esc(lane.model ?? DASH)}</span></span>` +
    '<span class="submeta-row"><span class="submeta-key">Context</span>' +
    `<span class="context-bar"><span class="context-fill" style="width:${contextPct}%"></span></span>` +
    `<span class="submeta-val mono">${esc(contextValue)}</span></span>` +
    '</div>'
  )
}

/**
 * Render the full waterfall: a time axis row then one lane per actor, with
 * left label columns and right time tracks.
 *
 * @param {ReturnType<typeof buildWaterfallLayout>} layout
 * @returns {string}
 */
export function renderWaterfall(layout) {
  const lanes = Array.isArray(layout?.lanes) ? layout.lanes : []
  const ticks = Array.isArray(layout?.axis?.ticks) ? layout.axis.ticks : []

  const axis =
    '<div class="waterfall-axis" data-waterfall-axis="true">' +
    '<div class="waterfall-axis-label"></div>' +
    '<div class="waterfall-axis-track">' +
    ticks
      .map(
        (tick) =>
          `<span class="axis-tick" style="left:${tick.pct}%">` +
          `<span class="axis-tick-label mono">${esc(tick.label)}</span></span>`
      )
      .join('') +
    '</div></div>'

  const laneHtml = lanes
    .map((lane) => {
      const blocks = lane.blocks.length
        ? lane.blocks.map(renderBlock).join('')
        : '<span class="lane-empty-note">—</span>'
      const submeta = lane.kind === 'agent' ? renderLaneSubmeta(lane) : ''
      return (
        `<div class="waterfall-lane lane-kind-${esc(lane.kind)}" data-lane-id="${esc(lane.id)}" ` +
        `data-lane-kind="${esc(lane.kind)}" style="--lane-color:${lane.color}">` +
        '<div class="lane-label-col">' +
        `<div class="lane-label"><span class="lane-ico" aria-hidden="true">${esc(lane.icon ?? '')}</span>` +
        `<span class="lane-name">${esc(lane.label)}</span></div>` +
        submeta +
        '</div>' +
        `<div class="lane-track" data-lane-track="${esc(lane.id)}">${blocks}</div>` +
        '</div>'
      )
    })
    .join('')

  return `<div class="waterfall-container" data-waterfall="true">${axis}${laneHtml}</div>`
}

/**
 * Render the complete run timeline: the run-strip band followed by the lane
 * waterfall.
 *
 * @param {ReturnType<typeof buildWaterfallLayout>} layout
 * @returns {string}
 */
export function renderWaterfallTimeline(layout) {
  if (!layout) return ''
  return `${renderRunStrip(layout.strip)}${renderWaterfall(layout)}`
}

export default buildBlueprintLayout
