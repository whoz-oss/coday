/**
 * Factory Cockpit — run detail view (`#view-detail`).
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * This view is the human-readable projection of ONE governed workflow. Its only
 * authority is Governed Projection v2 — there is no legacy JSONL run, no
 * `/api/runs`, and no line/done SSE stream anywhere in this module.
 *
 *   - `GET /api/factory/workflows/:id[?namespaceId=…]`          (projection)
 *   - `GET /api/factory/workflows/:id/timing[?namespaceId=…]`   (durations)
 *   - `GET /api/factory/workflows/:id/evidence[?namespaceId=…]` (recorded evidence)
 *   - `GET /api/factory/workflows/:id/metrics[?namespaceId=…]`  (operational rollup)
 *   - SSE `workflow-projection-updated`                        (live refresh)
 *
 * `namespaceId` is optional: when absent the query parameter is omitted from
 * every request instead of being sent empty.
 *
 * Lifecycle contract: `mount` performs the initial load, wires the delegated
 * click handler and the SSE subscription, and returns a handle whose `unmount`
 * is idempotent and leak-free: it clears the refresh timer, aborts in-flight
 * requests, unsubscribes the SSE listener and removes the DOM listener.
 */

import { WORKFLOW_PROJECTION_EVENTS } from '../services/sse-client.mjs'
import { normalizeSteps } from '../components/gantt.mjs'
import { buildWaterfallLayout, renderWaterfallTimeline } from '../components/temporal-lanes.mjs'
import { renderPhasePanel, loadPhaseEnrichment, findWaitingInteraction } from '../components/phase-panel.mjs'
import { esc, fmtDur } from '../components/facts.mjs'
import { buildCaseLinkHtml } from '../components/case-link.mjs'

/** Live-refresh event, derived from the shared SSE contract (never hard-coded twice). */
export const WORKFLOW_UPDATED_EVENT =
  WORKFLOW_PROJECTION_EVENTS.find((event) => event === 'workflow-projection-updated') ?? 'workflow-projection-updated'

const DEFAULT_REFRESH_DEBOUNCE_MS = 150

/**
 * Default human actor attributed to a cockpit decision. Matches the loopback
 * development principal (`TrustContext.LOOPBACK_DEV_PRINCIPAL_ID`) so the
 * `X-Factory-Actor-Id` header is always explicit; the server still owns the
 * authoritative identity and ignores this header outside loopback dev.
 */
export const DEFAULT_ACTOR_ID = 'local-dev-user'

/**
 * Build the STRICT reply body accepted by the interaction endpoint: only
 * `expectedRevision`, `actionId` and (when non-empty) `text` are ever sent, so a
 * malformed payload can never be produced by the cockpit.
 *
 * @param {object|null} interaction the waiting interaction (carries `revision`)
 * @param {string} actionId `approve` | `reject`
 * @param {string} [text] optional free-form comment (truncated to the contract max)
 * @returns {{ expectedRevision: number, actionId: string, text?: string }}
 */
export function buildReplyBody(interaction, actionId, text) {
  const body = { expectedRevision: Number(interaction?.revision), actionId }
  const trimmed = typeof text === 'string' ? text.trim() : ''
  if (trimmed) body.text = trimmed.slice(0, 2000)
  return body
}

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

function renderSwimlanes(lanesHtml) {
  return (
    '<div class="panel" data-run-detail-lanes="true">' + '<h2 class="panel-title">Timeline</h2>' + lanesHtml + '</div>'
  )
}

// The run waterfall is fed the Governed Projection v2 steps (which carry
// `lane`, `responsibility`, `startedAt`, `completedAt`, `durationMs`); the
// timing payload supplies the run origin when the projection omits it.
function renderTimeline(state, now) {
  const layout = buildWaterfallLayout(state.workflow, {
    now,
    startedAt: state.timing?.startedAt ?? state.timing?.createdAt ?? null,
  })
  return renderWaterfallTimeline(layout)
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
 *   namespaceId?: string | null,
 *   apiClient: { get: (path: string, options?: object) => Promise<any>, post?: (path: string, body?: any, options?: object) => Promise<any> },
 *   sseClient?: { on: (event: string, handler: Function) => (() => void) } | null,
 *   now?: () => number,
 *   agentosUrl?: string,
 *   codayExpressUrl?: string,
 *   actorId?: string | null,
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
  // `namespaceId` is OPTIONAL: when absent the scope query is omitted entirely
  // rather than emitted as an empty `namespaceId=`.
  const scope = typeof namespaceId === 'string' && namespaceId ? `namespaceId=${encodeURIComponent(namespaceId)}` : ''
  const withScope = (path) => (scope ? `${path}?${scope}` : path)

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
    checkpointSubmitting: false,
    checkpointFeedback: null,
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

    // Controller identity (case/thread) — rendered through the shared SSRF-safe
    // component. Clickable only against a trusted base supplied by the caller.
    const identityHtml = buildCaseLinkHtml(state.workflow.controllerExecution, {
      agentosUrl: options.agentosUrl,
      codayExpressUrl: options.codayExpressUrl,
    })
    const identity = identityHtml
      ? `<div class="run-detail-identity" data-run-detail-identity="true">${identityHtml}</div>`
      : ''

    return (
      '<div class="panel" data-run-detail-head="true">' +
      `<h2 class="panel-title" title="${esc(title)}">${esc(title)}</h2>` +
      `<div class="cockpit-id" data-workflow-id="${esc(workflowId)}" style="color:var(--faint);font-size:11px">${esc(
        workflowId
      )}</div>` +
      `<div class="metrics" style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px">${chips.join('')}</div>` +
      identity +
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

    // SSSF-style horizontal waterfall: run-strip + one lane per actor, with
    // phase blocks positioned in real time from the projection timestamps.
    // This is the SINGLE timeline of the detail view; the former Gantt block
    // ("ACTEUR · TEMPS") was a redundant duplicate and has been removed.
    const lanes = renderTimeline(state, now())
    const panel = renderPhasePanel({
      step: selectedStep(),
      workflow: state.workflow,
      evidence: state.evidence,
      enrichment: state.enrichment,
      loading: state.enrichmentLoading,
      checkpoint: { submitting: state.checkpointSubmitting, feedback: state.checkpointFeedback },
    })

    container.innerHTML = `<div class="run-detail" data-run-detail="true">${renderHeader()}${renderSwimlanes(
      lanes
    )}${panel}${renderMetricsStrip(state.metrics)}</div>`
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
      workflow = await apiClient.get(withScope(base), { signal })
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
      safeGet(withScope(`${base}/timing`), signal),
      safeGet(withScope(`${base}/evidence`), signal),
      safeGet(withScope(`${base}/metrics`), signal),
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

    const result = await loadPhaseEnrichment(step, {
      apiClient,
      signal: controller?.signal,
      workflowId,
      namespaceId: namespaceId ?? null,
    })
    if (!state.mounted || controller !== state.abortController || state.selectedStepId !== stepId) return
    state.enrichment = result
    state.enrichmentLoading = false
    render()
  }

  const selectStep = (stepId) => {
    if (!state.mounted || state.selectedStepId === stepId) return
    state.selectedStepId = stepId
    state.enrichment = null
    state.enrichmentLoading = false
    state.checkpointSubmitting = false
    state.checkpointFeedback = null
    render()
    if (state.selectedStepId) void loadEnrichment(state.selectedStepId)
  }

  /**
   * Resume the server-side sequencer after a checkpoint is resolved.
   *
   * The reply endpoint updates the durable interaction/projection but does NOT
   * re-trigger the in-process `SessionRunService.runSession` loop, so the DAG
   * would stay suspended: the cockpit therefore calls `POST .../continue`.
   * `continue` shares the `/run` contract and falls back to
   * `factory.session.default-repo-root` when no `repoRoot` is supplied (the same
   * convention the run launcher relies on). Continuation is best-effort: the
   * checkpoint is already resolved when this call fails.
   */
  const resumeAfterCheckpoint = async () => {
    try {
      const body = typeof namespaceId === 'string' && namespaceId ? { namespaceId } : {}
      await apiClient.post(`${base}/continue`, body, {
        signal: state.abortController?.signal,
        namespaceId: namespaceId ?? undefined,
        actorId: options.actorId ?? DEFAULT_ACTOR_ID,
      })
      return true
    } catch {
      // Best-effort: a misconfigured repoRoot must not mask a successful reply.
      return false
    }
  }

  const submitCheckpoint = async (actionId) => {
    if (!state.mounted || state.checkpointSubmitting) return
    const interaction = findWaitingInteraction(selectedStep(), state.enrichment)
    if (!interaction?.interactionId) {
      state.checkpointFeedback = { type: 'error', message: "Aucune interaction en attente pour cette étape." }
      render()
      return
    }

    const commentEl = container.querySelector?.('#checkpoint-comment')
    const text = typeof commentEl?.value === 'string' ? commentEl.value : ''

    state.checkpointSubmitting = true
    state.checkpointFeedback = null
    render()

    try {
      await apiClient.post(
        `${base}/interactions/${encodeURIComponent(interaction.interactionId)}/reply`,
        buildReplyBody(interaction, actionId, text),
        {
          signal: state.abortController?.signal,
          namespaceId: namespaceId ?? undefined,
          actorId: options.actorId ?? DEFAULT_ACTOR_ID,
        },
      )
    } catch (error) {
      if (!state.mounted) return
      state.checkpointSubmitting = false
      const conflict = error?.code === 'REVISION_CONFLICT' || error?.status === 409
      state.checkpointFeedback = {
        type: 'error',
        message: conflict
          ? 'Conflit de révision : interaction rechargée, réessayez.'
          : `Échec de la réponse : ${String(error?.message ?? error)}`,
      }
      if (conflict && state.selectedStepId) {
        // Re-fetch the interaction to acquire the new authoritative revision.
        await loadEnrichment(state.selectedStepId)
        if (state.mounted) {
          state.checkpointFeedback = {
            type: 'error',
            message: 'Conflit de révision : interaction rechargée, réessayez.',
          }
          render()
        }
        return
      }
      render()
      return
    }

    if (!state.mounted) return
    state.checkpointSubmitting = false
    state.checkpointFeedback = { type: 'success', message: 'Checkpoint résolu — reprise du workflow…' }
    // Drop the now-resolved interaction so no button is offered while the
    // sequencer resumes; the projected status may still read `blocked` until
    // the fresh loadAll lands.
    if (state.enrichment) state.enrichment.interaction = null
    render()
    const resumed = await resumeAfterCheckpoint()
    if (!state.mounted) return
    if (!resumed) {
      state.checkpointFeedback = {
        type: 'success',
        message: 'Checkpoint résolu. Reprise automatique impossible (repoRoot par défaut manquant ?).',
      }
    }
    await loadAll()
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
    if (namespaceId && payload.namespaceId !== undefined && payload.namespaceId !== namespaceId) return false
    return true
  }

  state.onClick = (event) => {
    const target = event?.target
    const closest = typeof target?.closest === 'function' ? target.closest.bind(target) : null
    const checkpointEl = closest?.('[data-checkpoint-action]') ?? null
    if (checkpointEl) {
      event?.preventDefault?.()
      void submitCheckpoint(checkpointEl.dataset.checkpointAction)
      return
    }

    if (closest?.('[data-phase-panel-close]')) {
      event?.preventDefault?.()
      selectStep(null)
      return
    }

    // The phase panel shares data-step-id with timeline blocks. Internal panel
    // clicks are interactions with the open inspector, never selection toggles.
    if (closest?.('[data-phase-panel]')) return

    const el = closest?.('[data-step-id]') ?? target
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
    state.checkpointSubmitting = false
    state.checkpointFeedback = null
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

export default { mount, WORKFLOW_UPDATED_EVENT, buildReplyBody, DEFAULT_ACTOR_ID }
