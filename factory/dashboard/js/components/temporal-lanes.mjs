/**
 * Factory Cockpit — temporal lanes component (Milestone D, Wave 2).
 *
 * Vanilla ESM, zero dependencies, zero build step. Turns a governed workflow
 * projection (or a workflow definition phase list) into three temporal
 * swimlanes keyed by actor kind:
 *
 *   - `human` — work performed by a person (approvals, decisions, reviews);
 *   - `agent` — any agent-performed work (including source edits);
 *   - `code`  — deterministic Factory execution (builds, tests, scans, oracles).
 *
 * The module is purely computational: `buildBlueprintLayout` returns a
 * structured layout, `renderTemporalLanes` serializes it to an HTML string.
 * Nothing touches `window` or `document`, so it is import-safe in Node.
 */

export const ACTOR_KINDS = Object.freeze(['human', 'agent', 'code'])

export const STEP_STATES = Object.freeze(['completed', 'active', 'pending', 'failed'])

export const LANE_LABELS = Object.freeze({ human: 'Humain', agent: 'Agent', code: 'Code' })

// Fallback heuristics used only when a step carries no explicit responsibility.
const CODE_HINTS = /(oracle|build|test|lint|scan|typecheck|type-check|compile|deterministic|verify|code)/i
const HUMAN_HINTS = /(human|humain|approv|validat|decision|manual|confirm|review|gate|revue)/i

/** Escape a value for safe HTML text interpolation. */
export function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * Resolve the actor kind of a step: explicit responsibility wins, otherwise a
 * deterministic hint-based fallback defaulting to `agent`.
 *
 * @param {{ responsibility?: { kind?: string }, id?: string, name?: string }} step
 * @returns {'human'|'agent'|'code'}
 */
export function classifyActorKind(step) {
  const kind = step?.responsibility?.kind
  if (kind === 'human' || kind === 'agent' || kind === 'code') return kind
  const haystack = `${step?.id ?? ''} ${step?.name ?? ''}`
  if (CODE_HINTS.test(haystack)) return 'code'
  if (HUMAN_HINTS.test(haystack)) return 'human'
  return 'agent'
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
  const status = step?.status ?? 'pending'
  const terminal = status === 'completed' || status === 'failed' || status === 'cancelled'
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

/**
 * Flatten a mixed list of phases and/or steps into a single ordered step list.
 * An item carrying an array `steps` is treated as a phase and unwrapped.
 *
 * @param {any} phases
 * @returns {any[]}
 */
function normalizeSteps(phases) {
  if (!phases) return []
  const list = Array.isArray(phases)
    ? phases
    : Array.isArray(phases.steps)
      ? [phases]
      : []
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
 * Build the temporal lane layout for a workflow blueprint.
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
      endedAt: typeof step?.endedAt === 'string' ? step.endedAt : null,
    }
    const node = {
      id: step?.id ?? `step-${index}`,
      name: step?.name ?? step?.id ?? `step-${index}`,
      status: step?.status ?? 'pending',
      state,
      actorKind,
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
 * Render the three temporal swimlanes as an HTML string.
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
            return (
              `<li class="lane-step state-${escapeHtml(node.state)}" data-step-id="${escapeHtml(node.id)}" ` +
              `data-state="${escapeHtml(node.state)}" style="${style}" title="${escapeHtml(node.name)}">` +
              `<span class="lane-step-name">${escapeHtml(node.name)}</span>` +
              `</li>`
            )
          })
          .join('')
      : '<li class="lane-step lane-empty" aria-hidden="true">—</li>'
    return (
      `<div class="lane lane-${kind}" data-lane="${kind}">` +
      `<div class="lane-title">${escapeHtml(LANE_LABELS[kind])}</div>` +
      `<ol class="lane-steps">${steps}</ol>` +
      `</div>`
    )
  }).join('')

  return `<div class="temporal-lanes${compactClass}">${body}</div>`
}

export default buildBlueprintLayout
