/**
 * Factory Cockpit — run detail view (`#view-detail`).
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * This view is the human-readable projection of ONE governed workflow. Its only
 * authority is Governed Projection v2 — there is no legacy JSONL run, no
 * `/api/runs`, and no line/done SSE stream anywhere in this module.
 *
 *   - `GET /api/factory/workflows/:id?namespaceId=…`          (projection, required)
 *   - `GET /api/factory/workflows/:id/timing?namespaceId=…`   (durations)
 *   - `GET /api/factory/workflows/:id/evidence?namespaceId=…` (recorded evidence)
 *   - `GET /api/factory/workflows/:id/metrics?namespaceId=…`  (operational rollup)
 *   - SSE `workflow-projection-updated`                        (live refresh)
 *
 * Lifecycle contract: `mount` performs the initial load, wires the delegated
 * click handler and the SSE subscription, and returns a handle whose `unmount`
 * is idempotent and leak-free: it clears the refresh timer, aborts in-flight
 * requests, unsubscribes the SSE listener and removes the DOM listener.
 */

import { WORKFLOW_PROJECTION_EVENTS } from '../services/sse-client.mjs'
import { normalizeSteps, renderGantt } from '../components/gantt.mjs'
import { renderPhasePanel, loadPhaseEnrichment } from '../components/phase-panel.mjs'
import { esc, fmtDur } from '../components/facts.mjs'

/** Live-refresh event, derived from the shared SSE contract (never hard-coded twice). */
export const WORKFLOW_UPDATED_EVENT =
  WORKFLOW_PROJECTION_EVENTS.find((event) => event === 'workflow-projection-updated') ?? 'workflow-projection-updated'

const DEFAULT_REFRESH_DEBOUNCE_MS = 150

function statusChipClass(status) {
  if (status === 'pass' || status === 'completed') return 'chip chip-success'
  if (status === 'fail' || status === 'failed' || status === 'cancelled') return 'chip chip-fail'
  if (status === 'blocked' || status === 'waiting_human') return 'chip chip-wave'
  return 'chip chip-running'
}

// `GET /api/factory/workflows/:id` returns `{ state: 'existing', projection, … }`
// for an existing workflow and `{ state: 'absent' | 'removed' | … }` otherwise.
// `isExistingWorkflow` is the single place that decides which of the two it is.
function isExistingWorkflow(workflow) {
  if (!workflow || typeof workflow !== 'object') return false
  if (workflow.state && workflow.state !== 'existing') return false
  return Boolean(workflow.projection)
}

function renderMetricsStrip(metrics) {
  const entries = Object.entries(metrics?.metrics ?? {})
  if (entries.length === 0) return ''
  const rows = entries
    .map(([key, value]) => {
      const available = value?.available === true
      const complete = value?.complete === true
      const durationMs = value?.value?.durationMs
      const state = available ? (complete ? 'complet' : 'partiel') : 'indisponible'
      const duration = Number.isFinite(durationMs) ? ` · ${fmtDur(durationMs)}` : ''
      return `<span class="chip" data-metric="${esc(key)}">${esc(key)}: ${esc(state + duration)}</span>`
    })
    .join('')
  return (
    '<div class="panel" data-metrics="true">' +
    '<h2 class="panel-title">Métriques opérationnelles</h2>' +
    `<div class="metrics" style="display:flex;gap:6px;flex-wrap:wrap">${rows}</div></div>`
  )
}

/**
 * Mount the run detail view.
 *
 * @param {any} container element-like target (typically `#view-detail`)
 * @param {{
 *   workflowId: string,
 *   namespaceId: string,
 *   apiClient: { get: (path: string, options?: object) => Promise<any> },
 *   sseClient?: { on: (event: string, handler: Function) => (() => void) } | null,
 *   now?: () => number,
 *   setTimeoutFn?: typeof setTimeout,
 *   clearTimeoutFn?: typeof clearTimeout,
 *   refreshDebounceMs?: number,
 * }} options
 * @returns {Promise<{ unmount: () => void, refresh: () => Promise<void>, selectStep: (id: string|null) => void,
 *   getState: () => object, isMounted: () => boolean, getPendingTimer: () => any }>}
 */
export async function mount(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('run-detail.mount requires a container')
  const { workflowId, namespaceId, apiClient } = options
  if (!workflowId || typeof workflowId !== 'string') throw new TypeError('run-detail.mount requires a workflowId')
  if (!namespaceId || typeof namespaceId !== 'string') throw new TypeError('run-detail.mount requires a namespaceId')
  if (!apiClient || typeof apiClient.get !== 'function') {
    throw new TypeError('run-detail.mount requires an apiClient with a get() method')
  }

  const sseClient = options.sseClient ?? null
  const now = typeof options.now === 'function' ? options.now : () => Date.now()
  const setTimeoutFn = options.setTimeoutFn ?? setTimeout
  const clearTimeoutFn = options.clearTimeoutFn ?? clearTimeout
  const refreshDebounceMs = Number.isFinite(options.refreshDebounceMs)
    ? options.refreshDebounceMs
    : DEFAULT_REFRESH_DEBOUNCE_MS

  const base = `/api/factory/workflows/${encodeURIComponent(workflowId)}`
  const scope = `namespaceId=${encodeURIComponent(namespaceId)}`

  const state = {
    mounted: true,
    phase: 'loading',
    error: null,
    workflow: null,
    timing: null,
    evidence: [],
    metrics: null,
    selectedStepId: null,
    enrichment: null,
    enrichmentLoading: false,
    refreshTimer: null,
    abortController: null,
    unsubscribe: null,
    onClick: null,
  }

  const steps = () => normalizeSteps(state.workflow, state.timing)
  const selectedStep = () => steps().find((step) => step.id === state.selectedStepId) ?? null

  const renderHeader = () => {
    if (state.phase === 'loading') return ''
    if (state.phase === 'error') {
      return (
        '<div class="panel" data-run-detail-error="true">' +
        '<h2 class="panel-title">Projection indisponible</h2>' +
        `<p class="placeholder">${esc(state.error ?? 'Erreur inconnue')}</p></div>`
      )
    }
    if (!isExistingWorkflow(state.workflow)) {
      const absentState = state.workflow?.state ?? 'absent'
      return (
        '<div class="panel" data-run-detail-absent="true">' +
        '<h2 class="panel-title">Workflow indisponible</h2>' +
        `<p class="placeholder">État : ${esc(absentState)} — aucune projection pour <code>${esc(
          workflowId
        )}</code>.</p></div>`
      )
    }

    const projection = state.workflow.projection
    const title = projection.title || projection.workflowType || workflowId
    const timing = state.timing ?? {}
    const chips = [
      `<span class="${statusChipClass(projection.status)}">${esc(projection.status)}</span>`,
      `<span class="chip">${esc(projection.workflowType)}</span>`,
      `<span class="chip">rév. ${esc(state.workflow.revision ?? '—')}</span>`,
      `<span class="chip">${esc(`${projection.steps?.length ?? 0} étapes`)}</span>`,
    ]
    if (Number.isFinite(timing.totalElapsedMs)) {
      chips.push(`<span class="chip">⏱ total ${esc(fmtDur(timing.totalElapsedMs))}</span>`)
    }
    if (Number.isFinite(timing.activeMs)) {
      chips.push(`<span class="chip">actif ${esc(fmtDur(timing.activeMs))}</span>`)
    }

    return (
      '<div class="panel" data-run-detail-head="true">' +
      `<h2 class="panel-title" title="${esc(title)}">${esc(title)}</h2>` +
      `<div class="cockpit-id" data-workflow-id="${esc(workflowId)}" style="color:var(--faint);font-size:11px">${esc(
        workflowId
      )}</div>` +
      `<div class="metrics" style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px">${chips.join('')}</div>` +
      '</div>'
    )
  }

  const render = () => {
    if (!state.mounted) return
    if (state.phase === 'loading') {
      container.innerHTML =
        '<div class="panel" data-run-detail-loading="true"><p class="placeholder">' +
        'Chargement de la projection…</p></div>'
      return
    }
    if (state.phase === 'error') {
      container.innerHTML = renderHeader()
      return
    }

    const currentSteps = steps()
    const gantt = renderGantt({
      workflow: state.workflow,
      timing: state.timing,
      steps: currentSteps,
      selectedStepId: state.selectedStepId,
      now: now(),
    })
    const panel = renderPhasePanel({
      step: selectedStep(),
      workflow: state.workflow,
      evidence: state.evidence,
      enrichment: state.enrichment,
      loading: state.enrichmentLoading,
    })

    container.innerHTML = `<div class="run-detail" data-run-detail="true">${renderHeader()}${gantt}${panel}${renderMetricsStrip(
      state.metrics
    )}</div>`
  }

  const safeGet = async (path, signal) => {
    try {
      return await apiClient.get(path, { signal })
    } catch {
      return null
    }
  }

  const loadAll = async () => {
    if (!state.mounted) return
    state.abortController?.abort?.()
    const controller = typeof AbortController === 'function' ? new AbortController() : null
    state.abortController = controller
    const signal = controller?.signal

    state.phase = 'loading'
    state.error = null
    render()

    let workflow
    try {
      workflow = await apiClient.get(`${base}?${scope}`, { signal })
    } catch (error) {
      if (!state.mounted || controller !== state.abortController) return
      state.phase = 'error'
      state.error = String(error?.message ?? error)
      state.workflow = null
      render()
      return
    }
    if (!state.mounted || controller !== state.abortController) return

    const [timingPayload, evidencePayload, metricsPayload] = await Promise.all([
      safeGet(`${base}/timing?${scope}`, signal),
      safeGet(`${base}/evidence?${scope}`, signal),
      safeGet(`${base}/metrics?${scope}`, signal),
    ])
    if (!state.mounted || controller !== state.abortController) return

    state.workflow = workflow && typeof workflow === 'object' ? workflow : null
    state.timing = timingPayload?.timing ?? null
    state.evidence = Array.isArray(evidencePayload?.items) ? evidencePayload.items : []
    state.metrics = metricsPayload ?? null
    state.phase = 'ready'
    render()

    if (state.selectedStepId) await loadEnrichment(state.selectedStepId)
  }

  const loadEnrichment = async (stepId) => {
    const step = steps().find((entry) => entry.id === stepId) ?? null
    if (!step) return
    const controller = state.abortController
    state.enrichmentLoading = true
    state.enrichment = null
    render()

    const result = await loadPhaseEnrichment(step, { apiClient, signal: controller?.signal })
    if (!state.mounted || controller !== state.abortController || state.selectedStepId !== stepId) return
    state.enrichment = result
    state.enrichmentLoading = false
    render()
  }

  const selectStep = (stepId) => {
    if (!state.mounted) return
    state.selectedStepId = state.selectedStepId === stepId ? null : stepId
    state.enrichment = null
    state.enrichmentLoading = false
    render()
    if (state.selectedStepId) void loadEnrichment(state.selectedStepId)
  }

  const scheduleRefresh = () => {
    if (!state.mounted || state.refreshTimer !== null) return
    state.refreshTimer = setTimeoutFn(() => {
      state.refreshTimer = null
      void loadAll()
    }, refreshDebounceMs)
  }

  const matchesWorkflow = (payload) => {
    if (!payload || typeof payload !== 'object') return false
    if (payload.workflowId !== undefined && payload.workflowId !== workflowId) return false
    if (payload.namespaceId !== undefined && payload.namespaceId !== namespaceId) return false
    return true
  }

  state.onClick = (event) => {
    const target = event?.target
    const el = typeof target?.closest === 'function' ? target.closest('[data-step-id]') : target
    const stepId = el?.dataset?.stepId ?? null
    if (stepId) selectStep(stepId)
  }
  container.addEventListener?.('click', state.onClick)

  if (sseClient && typeof sseClient.on === 'function') {
    state.unsubscribe = sseClient.on(WORKFLOW_UPDATED_EVENT, (payload) => {
      if (!state.mounted) return
      if (!matchesWorkflow(payload)) return
      scheduleRefresh()
    })
  }

  let unmounted = false
  const unmount = () => {
    if (unmounted) return
    unmounted = true
    state.mounted = false
    if (state.refreshTimer !== null) {
      clearTimeoutFn(state.refreshTimer)
      state.refreshTimer = null
    }
    state.abortController?.abort?.()
    state.abortController = null
    if (typeof state.unsubscribe === 'function') state.unsubscribe()
    state.unsubscribe = null
    if (state.onClick) container.removeEventListener?.('click', state.onClick)
    state.onClick = null
    container.innerHTML = ''
    state.workflow = null
    state.timing = null
    state.evidence = []
    state.metrics = null
    state.enrichment = null
    state.selectedStepId = null
  }

  await loadAll()

  return {
    unmount,
    refresh: () => loadAll(),
    selectStep,
    getState: () => state,
    isMounted: () => state.mounted,
    getPendingTimer: () => state.refreshTimer,
  }
}

export default { mount, WORKFLOW_UPDATED_EVENT }
