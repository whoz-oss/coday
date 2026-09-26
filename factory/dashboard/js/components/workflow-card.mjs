/**
 * Factory Cockpit — workflow card component (Milestone D, Wave 2).
 *
 * Vanilla ESM, zero dependencies, zero build step. Renders one governed
 * workflow projection snapshot as an HTML card: title, workflowId, status and
 * lifecycle chips, Coday case/thread identity, step progress, timing and
 * lifecycle action triggers (`restore`, `remove`, `purge`).
 *
 * STRICT SSRF INVARIANT — the AgentOS deep link is NEVER composed from
 * user-controlled input without a trusted base URL. Identity rendering is
 * delegated to `case-link.mjs`: its `buildAgentosCaseUrl` parses `agentosUrl`
 * with the WHATWG URL parser, refuses anything that is not an absolute
 * `http(s)` origin without credentials, and only then joins an encoded case id.
 * A `coday-express` thread stays unclickable unless a trusted server-configured
 * `codayExpressUrl` base is supplied — never a URL built from thread input.
 */

import { buildBlueprintLayout } from './temporal-lanes.mjs'
import { buildCaseLinkHtml, buildAgentosCaseUrl, escapeHtml, escapeAttr } from './case-link.mjs'

// Re-exported for backwards compatibility: `case-link.mjs` now owns the single
// SSRF choke point, but existing callers keep importing it from here.
export { buildAgentosCaseUrl, escapeHtml, escapeAttr }

export const LIFECYCLE_ACTIONS = Object.freeze([
  { action: 'restore', label: 'Restaurer', variant: 'btn-primary' },
  { action: 'remove', label: 'Supprimer', variant: 'btn-danger' },
  { action: 'purge', label: 'Purger', variant: 'btn-danger' },
])

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

/** Map a projection status to a cockpit chip class. */
export function statusChipClass(status) {
  switch (status) {
    case 'completed':
      return 'chip chip-success'
    case 'failed':
    case 'cancelled':
      return 'chip chip-fail'
    case 'running':
    case 'waiting_human':
      return 'chip chip-running'
    case 'blocked':
    case 'ready':
      return 'chip chip-wave'
    default:
      return 'chip'
  }
}

/** Resolve the lifecycle view state of a snapshot. */
function resolveLifecycle(snapshot, options) {
  if (snapshot?.state === 'removed') return 'removed'
  if (options?.mode === 'removed') return 'removed'
  return 'active'
}

/**
 * Render the Coday case/thread identity via the shared `case-link.mjs`
 * component. AgentOS cases may be linkable against the trusted `agentosUrl`;
 * Coday Express threads are clickable only against the trusted
 * `codayExpressUrl` (never otherwise).
 *
 * @param {{ kind?: string, caseId?: string, threadId?: string }} execution
 * @param {{ agentosUrl?: string, codayExpressUrl?: string }} [options]
 * @returns {string}
 */
function renderCaseIdentity(execution, options = {}) {
  return buildCaseLinkHtml(execution, {
    agentosUrl: options?.agentosUrl,
    codayExpressUrl: options?.codayExpressUrl,
  })
}

/** Resolve timing metadata from the many shapes a snapshot may carry. */
function resolveTiming(snapshot, projection) {
  const candidates = [snapshot?.timing, projection?.timing]
  for (const timing of candidates) {
    if (timing && typeof timing === 'object') return timing
  }
  return null
}

/**
 * Render a workflow card as an HTML string.
 *
 * Action buttons carry `data-action` / `data-workflow-id` attributes so the
 * projection view can handle them through a single delegated click listener
 * (and tests can assert the triggers without a browser).
 *
 * @param {object} snapshot workflow projection snapshot/item DTO
 * @param {{ agentosUrl?: string, codayExpressUrl?: string, mode?: 'active'|'removed', onAction?: Function }} [options]
 * @returns {string}
 */
export function renderWorkflowCard(snapshot = {}, options = {}) {
  const projection = snapshot?.projection ?? {}
  const execution = snapshot?.controllerExecution ?? {}
  const workflowId = snapshot?.workflowId ?? projection?.workflowId ?? ''
  const title = projection?.title ?? workflowId ?? 'Workflow'
  const status = projection?.status ?? snapshot?.status ?? 'pending'
  const lifecycle = resolveLifecycle(snapshot, options)

  const layout = buildBlueprintLayout(projection?.steps ?? [])
  const { totalSteps, completionRate } = layout.summary
  const completedSteps = layout.steps.filter((node) => node.state === 'completed').length
  const pct = totalSteps ? Math.round(completionRate * 100) : 0

  const timing = resolveTiming(snapshot, projection)
  const durationMs =
    (timing && Number.isFinite(timing.totalElapsedMs) && timing.totalElapsedMs) ||
    (timing && Number.isFinite(timing.durationMs) && timing.durationMs) ||
    (Number.isFinite(snapshot?.durationMs) && snapshot.durationMs) ||
    null
  const durationLabel = formatDuration(durationMs)

  const identity = renderCaseIdentity(execution, options)
  const revision = snapshot?.revision

  const laneChips = ['human', 'agent', 'code']
    .map((kind) => {
      const label = kind === 'human' ? 'Human' : kind === 'agent' ? 'Agent' : 'Code'
      const count = layout.summary.laneCounts[kind]
      return `<span class="chip lane-chip lane-chip-${kind}" data-lane-chip="${kind}">${label} ${count}</span>`
    })
    .join('')

  const isRemoved = lifecycle === 'removed'
  const restoreButton = isRemoved
    ? `<button type="button" class="btn btn-primary" data-action="restore" data-workflow-id="${escapeAttr(workflowId)}">Restaurer</button>`
    : ''
  const removeButton = isRemoved
    ? ''
    : `<button type="button" class="btn btn-danger" data-action="remove" data-workflow-id="${escapeAttr(workflowId)}">Supprimer</button>`
  const purgeButton = `<button type="button" class="btn btn-danger" data-action="purge" data-workflow-id="${escapeAttr(workflowId)}">Purger</button>`

  const meta = [
    `<span class="cockpit-id">${escapeHtml(workflowId)}</span>`,
    Number.isFinite(revision) ? `<span class="cockpit-id">r${escapeHtml(revision)}</span>` : '',
    identity,
  ]
    .filter(Boolean)
    .join('<span class="card-sep" aria-hidden="true">·</span>')

  return (
    `<article class="workflow-card" data-workflow-id="${escapeAttr(workflowId)}" data-state="${escapeHtml(lifecycle)}">` +
    `<header class="workflow-card-head">` +
    `<h3 class="workflow-card-title">${escapeHtml(title)}</h3>` +
    `<span class="${statusChipClass(status)}" data-status="${escapeHtml(status)}">${escapeHtml(status)}</span>` +
    `<span class="chip ${isRemoved ? 'chip-fail' : 'chip-success'}" data-lifecycle="${escapeHtml(lifecycle)}">${escapeHtml(lifecycle)}</span>` +
    `</header>` +
    `<div class="workflow-card-meta">${meta}</div>` +
    `<div class="workflow-card-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}">` +
    `<div class="workflow-card-progress-bar" style="--progress:${pct}%"></div>` +
    `<span class="workflow-card-progress-label">${completedSteps}/${totalSteps} étapes</span>` +
    `</div>` +
    `<div class="workflow-card-lanes">${laneChips}</div>` +
    (durationLabel ? `<div class="workflow-card-timing cockpit-duration">${escapeHtml(durationLabel)}</div>` : '') +
    `<div class="workflow-card-actions">${restoreButton}${removeButton}${purgeButton}</div>` +
    `</article>`
  )
}

export default renderWorkflowCard
