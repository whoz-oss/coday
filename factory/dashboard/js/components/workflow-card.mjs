/**
 * Factory Cockpit — workflow card component (Milestone D, Wave 2).
 *
 * Vanilla ESM, zero dependencies, zero build step. Renders one governed
 * workflow projection snapshot as a card that is a faithful visual replica of
 * the "Coday Dockyard" sandbox card: `.card` surface, `.card-head` with a mono
 * `.run-id`, a glowing `.chip` status, phase `.dots`, a `.card-meta` line, a
 * `COST / RUNTIME / TOKENS` `.stat` row, one `.session` sub-card per step and an
 * `.actions` row (`restore`, `remove`, `purge`).
 *
 * MISSING-DATA RULE — the Factory projection carries neither cost nor tokens, so
 * those stats render the literal `-`. Runtime is derived from
 * `startedAt → completedAt` (falling back to the timing summary) and is `-`
 * when unavailable.
 *
 * STRICT SSRF INVARIANT — the AgentOS deep link is NEVER composed from
 * user-controlled input without a trusted base URL. Identity rendering is
 * delegated to `case-link.mjs`.
 */

import { buildCaseLinkHtml, buildAgentosCaseUrl, escapeHtml, escapeAttr } from './case-link.mjs'

// Re-exported for backwards compatibility: `case-link.mjs` now owns the single
// SSRF choke point, but existing callers keep importing it from here.
export { buildAgentosCaseUrl, escapeHtml, escapeAttr }

export const LIFECYCLE_ACTIONS = Object.freeze([
  { action: 'restore', label: 'Restaurer', variant: 'btn-primary' },
  { action: 'remove', label: 'Supprimer', variant: 'btn-danger' },
  { action: 'purge', label: 'Purger', variant: 'btn-danger' },
])

/** Literal placeholder for any value the Factory projection does not provide. */
export const DASH = '-'

/** Human-readable duration for a millisecond count. */
export function formatDuration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return null
  if (ms < 1000) return `${Math.round(ms)} ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)} s`
  const minutes = seconds / 60
  if (minutes < 60) return `${minutes.toFixed(1)} min`
  return `${(minutes / 60).toFixed(1)} h`
}

/**
 * Map a projection status onto one of the four Dockyard chip variants
 * (`success` | `fail` | `running` | `queued`).
 *
 * @param {string} status
 * @returns {'success'|'fail'|'running'|'queued'}
 */
export function chipKind(status) {
  switch (status) {
    case 'completed':
    case 'pass':
      return 'success'
    case 'failed':
    case 'cancelled':
    case 'fail':
      return 'fail'
    case 'running':
    case 'waiting_human':
      return 'running'
    default:
      return 'queued'
  }
}

/** Human label for a chip / phase-dot state. */
export function stateLabel(kind, status) {
  if (kind === 'success') return 'réussi'
  if (kind === 'fail') return 'échoué'
  if (kind === 'running') return 'en cours'
  return status === 'blocked' ? 'bloqué' : 'en attente'
}

/** Map a projection status to a cockpit chip class (legacy contract). */
export function statusChipClass(status) {
  const kind = chipKind(status)
  if (kind === 'success') return 'chip chip-success'
  if (kind === 'fail') return 'chip chip-fail'
  if (kind === 'running') return 'chip chip-running'
  return 'chip'
}

/** Resolve the lifecycle view state of a snapshot. */
function resolveLifecycle(snapshot, options) {
  if (snapshot?.state === 'removed') return 'removed'
  if (options?.mode === 'removed') return 'removed'
  return 'active'
}

/** Inline SVG glyphs (stroke = `currentColor`, so each chip/dot keeps its hue). */
const SVG_OPEN =
  '<svg class="ico" width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" ' +
  'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
const ICONS = Object.freeze({
  success: `${SVG_OPEN}<path d="M3 8.4 6.4 12 13 4.6"/></svg>`,
  fail: `${SVG_OPEN}<path d="M4 4l8 8M12 4l-8 8"/></svg>`,
  running:
    '<svg class="ico spin" width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" ' +
    'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M8 1.6a6.4 6.4 0 1 1-6.4 6.4"/></svg>',
  queued: `${SVG_OPEN}<circle cx="8" cy="8" r="5"/></svg>`,
  cost: `${SVG_OPEN}<path d="M8 1.8v12.4M10.8 4.6H6.6a2.2 2.2 0 0 0 0 4.4h2.8a2.2 2.2 0 0 1 0 4.4H5.2"/></svg>`,
  runtime: `${SVG_OPEN}<circle cx="8" cy="8" r="6.2"/><path d="M8 4.4V8l2.4 1.6"/></svg>`,
  tokens: `${SVG_OPEN}<rect x="2.6" y="2.6" width="10.8" height="10.8" rx="2.4"/><path d="M6 2.6v10.8M10 2.6v10.8"/></svg>`,
})

/** Coerce an instant (ISO string or epoch ms) to epoch ms, else `NaN`. */
function toMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : Number.NaN
  }
  return Number.NaN
}

/** Duration of a single step in ms, or `null` when the projection lacks it. */
function stepDurationMs(step) {
  if (Number.isFinite(step?.durationMs)) return Number(step.durationMs)
  const start = toMs(step?.startedAt)
  const end = toMs(step?.completedAt)
  if (Number.isFinite(start) && Number.isFinite(end) && end >= start) return end - start
  return null
}

/** Run runtime in ms from `startedAt → completedAt`, else the timing summary. */
function runDurationMs(snapshot, projection) {
  const steps = Array.isArray(projection?.steps) ? projection.steps : []
  const starts = steps.map((step) => toMs(step?.startedAt)).filter(Number.isFinite)
  const ends = steps
    .map((step) => {
      const start = toMs(step?.startedAt)
      if (!Number.isFinite(start)) return Number.NaN
      const duration = stepDurationMs(step)
      return start + (duration ?? 0)
    })
    .filter(Number.isFinite)
  if (starts.length > 0 && ends.length > 0) {
    const span = Math.max(...ends) - Math.min(...starts)
    if (span > 0) return span
  }
  const timing = snapshot?.timing ?? projection?.timing
  if (Number.isFinite(timing?.totalElapsedMs)) return timing.totalElapsedMs
  if (Number.isFinite(timing?.durationMs)) return timing.durationMs
  if (Number.isFinite(snapshot?.durationMs)) return snapshot.durationMs
  return null
}

/** Render a status chip with its inline glyph. */
function renderChip(status) {
  const kind = chipKind(status)
  return (
    `<span class="chip ${kind}" data-status="${escapeAttr(status)}">${ICONS[kind]}` +
    `${escapeHtml(stateLabel(kind, status))}</span>`
  )
}

/** Render one phase dot per step, coloured by state. */
function renderDots(steps) {
  const dots = steps
    .map((step, index) => {
      const kind = chipKind(step?.status)
      const glyph = kind === 'running' ? '◐' : '●'
      const name = step?.name ?? step?.id ?? `étape ${index + 1}`
      const title = `${name} : ${stateLabel(kind, step?.status)}`
      return `<span class="d ${kind}" data-state="${escapeAttr(kind)}" title="${escapeAttr(title)}">${glyph}</span>`
    })
    .join('')
  return `<span class="dots" data-phase-dots="true">${dots}</span>`
}

/** Render a single Dockyard `.stat` chip (icon + label + mono value). */
function renderStat(icon, label, value) {
  return (
    `<span class="stat" data-stat="${escapeAttr(label.toLowerCase())}">` +
    `<span class="stat-ico" aria-hidden="true">${ICONS[icon]}</span>` +
    `<span class="stat-key">${escapeHtml(label)}</span>` +
    `<b>${escapeHtml(value)}</b></span>`
  )
}

/** Render the COST / RUNTIME / TOKENS stat row. */
function renderStats(runtimeMs) {
  const runtime = formatDuration(runtimeMs) ?? DASH
  return `<div class="stats-row">${renderStat('cost', 'COST', DASH)}${renderStat('runtime', 'RUNTIME', runtime)}${renderStat(
    'tokens',
    'TOKENS',
    DASH
  )}</div>`
}

/** Render the card meta line: `projet › titre / branche · type`. */
function renderMeta(snapshot, projection) {
  const project = snapshot?.namespaceId || projection?.projectId || DASH
  const title = projection?.title || snapshot?.workflowId || DASH
  const branch = snapshot?.branch ?? projection?.branch ?? null
  const type = projection?.workflowType || DASH
  const sep = '<span class="card-sep" aria-hidden="true">·</span>'
  const arrow = '<span class="card-sep" aria-hidden="true">›</span>'
  return (
    `<div class="card-meta">` +
    `<span>${escapeHtml(project)}</span>${arrow}` +
    `<span>${escapeHtml(title)}</span>` +
    (branch ? `<span class="card-sep" aria-hidden="true">/</span><span>${escapeHtml(branch)}</span>` : '') +
    `${sep}<span>${escapeHtml(type)}</span>` +
    `</div>`
  )
}

/** Render one `.session` sub-card (a step, with its own stats). */
function renderSession(step, index) {
  const id = step?.id ?? `step-${index + 1}`
  const name = step?.name ?? id
  const kind = chipKind(step?.status)
  const duration = formatDuration(stepDurationMs(step)) ?? DASH
  return (
    `<div class="session" data-step-id="${escapeAttr(id)}">` +
    `<div class="s-main">` +
    `<span class="s-id">${escapeHtml(id)}</span>` +
    `<span class="s-adw">${escapeHtml(name)}</span>` +
    `<span class="chip ${kind}">${ICONS[kind]}${escapeHtml(stateLabel(kind, step?.status))}</span>` +
    `</div>` +
    `<div class="stats-row">${renderStat('runtime', 'DURÉE', duration)}${renderStat('cost', 'COST', DASH)}${renderStat(
      'tokens',
      'TOKENS',
      DASH
    )}</div>` +
    `</div>`
  )
}

/**
 * Render a workflow card as an HTML string.
 *
 * Action buttons carry `data-action` / `data-workflow-id` attributes so the
 * projection view can handle them through a single delegated click listener
 * (and tests can assert the triggers without a browser). The card itself stays
 * clickable (`data-workflow-id`) to open the detail timeline.
 *
 * @param {object} snapshot workflow projection snapshot/item DTO
 * @param {{ agentosUrl?: string, codayExpressUrl?: string, mode?: 'active'|'removed', onAction?: Function }} [options]
 * @returns {string}
 */
export function renderWorkflowCard(snapshot = {}, options = {}) {
  const projection = snapshot?.projection ?? {}
  const execution = snapshot?.controllerExecution ?? {}
  const workflowId = snapshot?.workflowId ?? projection?.workflowId ?? ''
  // Namespace attribution carried by a scope-wide list item; lets the cockpit
  // route the card click to the namespace-scoped detail timeline.
  const namespaceId = snapshot?.namespaceId ?? ''
  const status = projection?.status ?? snapshot?.status ?? 'pending'
  const lifecycle = resolveLifecycle(snapshot, options)
  const isRemoved = lifecycle === 'removed'

  const steps = Array.isArray(projection?.steps) ? projection.steps : []
  const kind = chipKind(status)
  const cardState = kind === 'running' ? ' running' : kind === 'fail' ? ' fail' : ''

  const revision = snapshot?.revision
  const side = [revision !== undefined && revision !== null ? `r${revision}` : null, namespaceId || null]
    .filter(Boolean)
    .join(' · ')

  const identity = buildCaseLinkHtml(execution, {
    agentosUrl: options.agentosUrl,
    codayExpressUrl: options.codayExpressUrl,
  })

  const detailButton =
    `<button type="button" class="btn" data-open-detail data-workflow-id="${escapeAttr(workflowId)}">` +
    `Ouvrir le détail</button>`
  const restoreButton = isRemoved
    ? `<button type="button" class="btn" data-action="restore" data-workflow-id="${escapeAttr(workflowId)}">Restaurer</button>`
    : ''
  const removeButton = isRemoved
    ? ''
    : `<button type="button" class="btn danger" data-action="remove" data-workflow-id="${escapeAttr(workflowId)}">Supprimer</button>`
  const purgeButton =
    `<button type="button" class="btn danger" data-action="purge" data-workflow-id="${escapeAttr(workflowId)}">` +
    `Purger</button>`

  return (
    `<article class="card workflow-card${cardState}" data-workflow-id="${escapeAttr(workflowId)}" ` +
    `data-namespace-id="${escapeAttr(namespaceId)}" data-state="${escapeHtml(lifecycle)}">` +
    `<div class="card-head">` +
    `<span class="run-id" title="${escapeAttr(workflowId)}">${escapeHtml(workflowId)}</span>` +
    renderChip(status) +
    renderDots(steps) +
    `<span class="card-side">${escapeHtml(side || DASH)}</span>` +
    `</div>` +
    renderMeta(snapshot, projection) +
    (identity ? `<div class="card-identity">${identity}</div>` : '') +
    renderStats(runDurationMs(snapshot, projection)) +
    (steps.length > 0 ? `<div class="sessions">${steps.map(renderSession).join('')}</div>` : '') +
    `<div class="actions">${detailButton}${restoreButton}${removeButton}${purgeButton}</div>` +
    `</article>`
  )
}

export default renderWorkflowCard
