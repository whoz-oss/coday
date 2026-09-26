/**
 * Factory Cockpit — governed delivery panel.
 *
 * Vanilla ESM, zero dependencies, zero build step. Reconstructs the Angular
 * `DeliveryPanelComponent` in the vanilla cockpit: it renders the forward
 * delivery lifecycle, the recorded operations, the reconciliation warnings and
 * the rollback requests, and exposes the delivery operations
 * (`checkpoint`, `push`, `pull-request`, `promote`) as trigger buttons.
 *
 * Authority: the Factory delivery control plane. The panel is display-only plus
 * an explicit, user-triggered POST; it never infers a stage of its own.
 *
 * SECURITY: every dynamic value is escaped with {@link esc}. The only clickable
 * external link is a GitHub pull-request URL, gated through {@link trustedUrl}
 * which refuses anything that is not `https://github.com` / `www.github.com`.
 *
 * The module is import-safe in Node: no `window`/`document` access happens at
 * module evaluation, only inside {@link mountDeliveryPanel}.
 */

/** Forward lifecycle stages, in order. */
export const DELIVERY_STAGES = Object.freeze([
  'implementation-ready',
  'artifact-ready',
  'release-approved',
  'deployed',
  'production-verified',
])

/** Delivery operations exposed as explicit, user-triggered POST requests. */
export const DELIVERY_OPERATIONS = Object.freeze(['checkpoint', 'push', 'pull-request', 'promote'])

/** Human labels for the delivery operations. */
export const DELIVERY_OPERATION_LABELS = Object.freeze({
  checkpoint: 'Checkpoint',
  push: 'Push',
  'pull-request': 'Pull request',
  promote: 'Promote',
})

/** Only these hosts may ever receive a clickable link. */
export const TRUSTED_URL_HOSTS = Object.freeze(['github.com', 'www.github.com'])

/** Escape a value for safe HTML text interpolation. */
export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * Return the URL only when it is an `https` URL on an allow-listed GitHub host.
 * Anything else (malformed, other protocol, other host, credentials) → `null`.
 *
 * @param {string} url
 * @returns {string|null}
 */
export function trustedUrl(url) {
  if (typeof url !== 'string' || !url.trim()) return null
  let parsed
  try {
    parsed = new URL(url)
  } catch {
    return null
  }
  if (parsed.protocol !== 'https:') return null
  if (!TRUSTED_URL_HOSTS.includes(parsed.hostname)) return null
  return parsed.toString()
}

/** Index of the current stage in the forward lifecycle, or `-1`. */
export function stageIndex(stage) {
  return DELIVERY_STAGES.indexOf(stage)
}

/**
 * Compute the display state of every lifecycle stage.
 *
 * @param {{ stage?: string }|null} delivery
 * @returns {Array<{ stage: string, done: boolean, current: boolean }>}
 */
export function stageItems(delivery) {
  const current = delivery?.stage ? stageIndex(delivery.stage) : -1
  return DELIVERY_STAGES.map((stage, index) => ({
    stage,
    done: current >= 0 && index < current,
    current: index === current,
  }))
}

/** Human-readable timestamp, falling back to the raw value. */
export function formatTimestamp(value) {
  if (!value) return 'Non enregistré'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString('fr-FR')
}

/** Build the delivery endpoint path (optionally for one operation). */
export function deliveryPath(workflowId, operation = '') {
  const base = `/api/factory/workflows/${encodeURIComponent(workflowId)}/delivery`
  const op = operation ? `/${encodeURIComponent(operation)}` : ''
  return `${base}${op}`
}

function renderStages(items) {
  const cells = items
    .map((item) => {
      const cls = item.current ? 'stage current' : item.done ? 'stage done' : 'stage'
      const suffix = item.current ? ' <span>— current</span>' : item.done ? ' <span>— complete</span>' : ''
      const state = item.current ? 'current' : item.done ? 'done' : 'pending'
      return (
        `<li class="${cls}" data-delivery-stage="${esc(item.stage)}" data-delivery-state="${state}">` +
        `${esc(item.stage)}${suffix}</li>`
      )
    })
    .join('')
  return `<ol class="timeline" data-delivery-timeline="true">${cells}</ol>`
}

function renderPullRequest(delivery) {
  const pr = delivery?.git?.pullRequest
  if (!pr) return '<p class="blocked">Pull request non créée ou adaptateur non configuré.</p>'
  const href = trustedUrl(pr.url)
  const label = `PR #${esc(pr.id)} · ${pr.draft ? 'draft' : esc(pr.state)}`
  if (!href) return `<p class="blocked" data-delivery-pr="untrusted">${label}</p>`
  return (
    `<a class="delivery-pr" data-delivery-pr="trusted" href="${esc(href)}" target="_blank" ` +
    `rel="noopener noreferrer">${label}</a>`
  )
}

function renderOperations(operations) {
  const list = Array.isArray(operations) ? operations : []
  if (list.length === 0) return '<p class="placeholder">Aucune opération de livraison enregistrée.</p>'
  return (
    '<ul class="records" data-delivery-operations="true">' +
    list
      .map((operation) => {
        const target = operation?.targetRef?.targetId ?? 'Non exposé'
        return (
          '<li class="delivery-operation" data-operation-id="' +
          esc(operation?.operationId) +
          '">' +
          `<div class="record-heading"><strong>${esc(operation?.kind)}</strong>` +
          `<span class="chip" data-operation-state="${esc(operation?.state)}">${esc(operation?.state)}</span></div>` +
          `<dl class="compact"><dt>Target</dt><dd><code>${esc(target)}</code></dd>` +
          `<dt>Attempt</dt><dd>${esc(operation?.attempt)}</dd>` +
          `<dt>Requested</dt><dd>${esc(formatTimestamp(operation?.requestedAt))}</dd></dl>` +
          '</li>'
        )
      })
      .join('') +
    '</ul>'
  )
}

function renderRollbackRequests(requests) {
  const list = Array.isArray(requests) ? requests : []
  if (list.length === 0) return '<p class="placeholder">Aucune demande de rollback.</p>'
  return (
    '<ul class="records" data-delivery-rollbacks="true">' +
    list
      .map(
        (request) =>
          '<li class="delivery-rollback" data-rollback-id="' +
          esc(request?.rollbackRequestId) +
          '">' +
          `<div class="record-heading"><strong>Target <code>${esc(request?.targetId)}</code></strong>` +
          `<span class="chip" data-rollback-status="${esc(request?.status)}">${esc(request?.status)}</span></div>` +
          `<dl class="compact"><dt>Reason</dt><dd>${esc(request?.reasonCode)}</dd>` +
          `<dt>Requested</dt><dd>${esc(formatTimestamp(request?.requestedAt))}</dd></dl>` +
          '</li>'
      )
      .join('') +
    '</ul>'
  )
}

function renderOperationButtons(working) {
  return (
    '<div class="delivery-actions" data-delivery-actions="true">' +
    DELIVERY_OPERATIONS.map((operation) => {
      const disabled = working === operation ? ' disabled' : ''
      const label =
        working === operation ? `${DELIVERY_OPERATION_LABELS[operation]}…` : DELIVERY_OPERATION_LABELS[operation]
      return `<button type="button" class="btn" data-delivery-op="${esc(operation)}"${disabled}>${esc(label)}</button>`
    }).join('') +
    '</div>'
  )
}

/**
 * Render the delivery panel as an escaped HTML string.
 *
 * @param {object|null} delivery `FactoryDeliverySnapshotDto` or `null`
 * @param {{ loading?: boolean, error?: string|null, working?: string|null }} [options]
 * @returns {string}
 */
export function renderDeliveryPanel(delivery, options = {}) {
  const { loading = false, error = null, working = null } = options

  const head =
    '<header class="delivery-head">' +
    '<h3 class="panel-title" style="margin:0">Governed delivery</h3>' +
    '<span class="chip authority">Factory authoritative · read only</span>' +
    '</header>'

  const notices =
    (loading
      ? '<p role="status" class="placeholder" data-delivery-loading="true">Chargement de la livraison…</p>'
      : '') + (error ? `<p role="alert" class="error" data-delivery-error="true">${esc(error)}</p>` : '')

  if (!delivery) {
    const body = loading
      ? ''
      : '<p class="placeholder" data-delivery-empty="true">Livraison non encore liée à cette unité de travail.</p>'
    return `<section class="delivery-panel panel" data-delivery-panel="true">${head}${notices}${body}</section>`
  }

  const indeterminate = Array.isArray(delivery.unresolvedIndeterminate) ? delivery.unresolvedIndeterminate : []
  const reconciliation = indeterminate.length
    ? '<section class="indeterminate-warning" role="alert" data-delivery-indeterminate="' +
      indeterminate.length +
      '">' +
      `<h4>Reconciliation requise</h4><p>${indeterminate.length} opération(s) indéterminée(s) restent non résolues. ` +
      'La Factory ne rejoue rien automatiquement.</p></section>'
    : ''

  const blockers = (Array.isArray(delivery.blockers) ? delivery.blockers : [])
    .map(
      (blocker) => `<p class="blocked">${esc(blocker?.code)}${blocker?.message ? ` — ${esc(blocker.message)}` : ''}</p>`
    )
    .join('')

  const facts =
    '<dl class="delivery-facts">' +
    `<div><dt>Branch</dt><dd><code>${esc(delivery.branch)}</code></dd></div>` +
    `<div><dt>Observed HEAD</dt><dd><code>${esc(delivery.headCommit)}</code></dd></div>` +
    `<div><dt>Artifact</dt><dd>${esc(delivery.artifact?.state)}</dd></div>` +
    `<div><dt>Release</dt><dd>${esc(delivery.release?.state)}</dd></div>` +
    `<div><dt>Deployment</dt><dd>${esc(delivery.deployment?.state)}</dd></div>` +
    `<div><dt>Production</dt><dd>${esc(delivery.verification?.state)}</dd></div>` +
    `<div><dt>Stage</dt><dd>${esc(delivery.stage)}</dd></div>` +
    '</dl>'

  return (
    `<section class="delivery-panel panel" data-delivery-panel="true" data-delivery-stage="${esc(delivery.stage)}">` +
    head +
    notices +
    renderStages(stageItems(delivery)) +
    '<p class="note">L’historique de rollback est un historique opérationnel : il ne rembobine pas le cycle de vie ' +
    'forward.</p>' +
    facts +
    renderPullRequest(delivery) +
    blockers +
    reconciliation +
    '<section data-delivery-operations-section="true"><h4>Opérations de livraison</h4>' +
    renderOperations(delivery.deliveryOperations) +
    '</section>' +
    '<section data-delivery-rollbacks-section="true"><h4>Demandes de rollback</h4>' +
    renderRollbackRequests(delivery.rollbackRequests) +
    '</section>' +
    renderOperationButtons(working) +
    '</section>'
  )
}

/**
 * Mount the delivery panel into a container element.
 *
 * @param {any} container element-like target
 * @param {{
 *   workflowId: string,
 *   namespaceId: string,
 *   caseId?: string,
 *   apiClient: { get: Function, post: Function },
 * }} options
 * @returns {{ unmount: Function, refresh: Function, trigger: Function, getState: Function, render: Function }}
 */
export function mountDeliveryPanel(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('mountDeliveryPanel requires a container')
  const { workflowId, namespaceId, caseId } = options
  if (!workflowId || typeof workflowId !== 'string') throw new TypeError('mountDeliveryPanel requires a workflowId')
  if (!namespaceId || typeof namespaceId !== 'string') throw new TypeError('mountDeliveryPanel requires a namespaceId')
  if (!options.apiClient || typeof options.apiClient.get !== 'function') {
    throw new TypeError('mountDeliveryPanel requires an apiClient with a get() method')
  }

  const apiClient = options.apiClient
  const attribution = { namespaceId, ...(caseId ? { caseId } : {}) }
  const state = { loading: false, error: null, delivery: null, working: null }
  let disposed = false

  const render = () => {
    if (disposed) return
    container.innerHTML = renderDeliveryPanel(state.delivery, {
      loading: state.loading,
      error: state.error,
      working: state.working,
    })
  }

  const load = async () => {
    if (disposed) return state.delivery
    state.loading = true
    state.error = null
    render()
    try {
      const payload = await apiClient.get(deliveryPath(workflowId), { attribution })
      if (disposed) return null
      state.delivery = payload ?? null
      state.error = null
    } catch (error) {
      if (disposed) return null
      state.delivery = null
      state.error = String(error?.message ?? error)
    } finally {
      if (!disposed) {
        state.loading = false
        render()
      }
    }
    return state.delivery
  }

  const trigger = async (operation) => {
    if (disposed) return { ok: false, operation, error: 'unmounted' }
    if (!DELIVERY_OPERATIONS.includes(operation)) return { ok: false, operation, error: 'UNKNOWN_OPERATION' }
    if (!apiClient || typeof apiClient.post !== 'function') return { ok: false, operation, error: 'NO_POST' }
    state.working = operation
    state.error = null
    render()
    try {
      const result = await apiClient.post(deliveryPath(workflowId, operation), {}, { attribution })
      if (disposed) return { ok: false, operation, error: 'unmounted' }
      state.working = null
      await load()
      return { ok: true, operation, result }
    } catch (error) {
      if (disposed) return { ok: false, operation, error: 'unmounted' }
      state.working = null
      state.error = String(error?.message ?? error)
      render()
      return { ok: false, operation, error: state.error }
    }
  }

  const onClick = (event) => {
    const operation = event?.target?.closest?.('[data-delivery-op]')?.dataset?.deliveryOp
    if (operation) void trigger(operation)
  }
  container.addEventListener?.('click', onClick)

  const unmount = () => {
    if (disposed) return
    disposed = true
    container.removeEventListener?.('click', onClick)
    container.innerHTML = ''
    state.delivery = null
  }

  const ready = load()

  return {
    unmount,
    refresh: load,
    trigger,
    render,
    ready,
    getState: () => ({ ...state }),
  }
}

export default { mountDeliveryPanel, renderDeliveryPanel, trustedUrl, stageItems, stageIndex, DELIVERY_STAGES }
