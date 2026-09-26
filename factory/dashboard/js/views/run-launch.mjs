/**
 * Factory Cockpit — governed workflow run launch view (`#view-launch`).
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * This view is the single entry point to START a governed workflow run. Its
 * only launch authority is the governed frontend route:
 *
 *   - `GET  /api/factory/workflow-definitions`        → selectable definitions (`items`)
 *   - `GET  /api/agents?namespaceId=…`                → selectable agents (AgentOS proxy)
 *   - `POST /api/factory/workflows/:workflowId/run`   → `{ namespaceId, ticket? }`
 *
 * The legacy JSONL `POST /api/factory/runs` route is NEVER used here. On a
 * `200`/`201` the view hands off to the run detail view with the targeted
 * `workflowId` + `namespaceId` (`#/detail?workflowId=…&namespaceId=…`).
 *
 * Lifecycle contract: `mountRunLaunchView` performs the initial loads, wires
 * the delegated form listeners and returns a handle whose `unmount` is
 * idempotent and leak-free (aborts in-flight requests, clears pending timers,
 * detaches DOM listeners and empties the container).
 *
 * The module is import-safe outside the browser: nothing touches `window` or
 * `document` until {@link mountRunLaunchView} runs.
 */

import { esc } from '../components/facts.mjs'

/** Cockpit route that hosts this view. */
export const LAUNCH_ROUTE = '/launch'
/** Cockpit route the view transitions to on a successful launch. */
export const DETAIL_ROUTE = '/detail'

/** Governed run route for a workflow id. */
export function buildRunUrl(workflowId) {
  return `/api/factory/workflows/${encodeURIComponent(workflowId)}/run`
}

/** Legacy route the view must never call. Exported for regression assertions. */
export function buildLegacyRunUrl() {
  return '/api/factory/runs'
}

/** Canonical detail hash carrying the targeted scope. */
export function buildDetailHash(workflowId, namespaceId) {
  return `${DETAIL_ROUTE}?workflowId=${encodeURIComponent(workflowId)}&namespaceId=${encodeURIComponent(namespaceId)}`
}

/** True when the error is a launch conflict (409 / lifecycle conflict). */
export function isConflictError(error) {
  if (!error) return false
  if (Number(error.status) === 409) return true
  return error.code === 'HTTP_409' || error.code === 'REVISION_CONFLICT'
}

/** Normalize a listing payload into an array, tolerating several envelopes. */
function listOf(payload, keys) {
  if (Array.isArray(payload)) return payload
  if (payload && typeof payload === 'object') {
    for (const key of keys) if (Array.isArray(payload[key])) return payload[key]
  }
  return []
}

/** Stable id used to launch a definition (`workflowType` is the registry key). */
export function definitionId(definition) {
  return definition?.workflowType ?? definition?.workflowId ?? definition?.id ?? ''
}

/** Human label of a workflow definition. */
export function definitionLabel(definition) {
  const id = definitionId(definition)
  const version = definition?.version ? ` @${definition.version}` : ''
  const title = definition?.title && definition.title !== id ? ` — ${definition.title}` : ''
  return `${id}${version}${title}`
}

/** Normalize the definitions listing (`{ items }` or a raw array). */
export function normalizeDefinitions(payload) {
  return listOf(payload, ['items', 'workflowDefinitions', 'definitions'])
    .map((definition) => ({
      id: definitionId(definition),
      name: definition?.title ?? definitionId(definition),
      version: definition?.version ?? '',
    }))
    .filter((definition) => definition.id)
}

/** Normalize the agents listing (AgentOS proxy shapes). */
export function normalizeAgents(payload) {
  return listOf(payload, ['items', 'agents', 'agentConfigs', 'data'])
    .map((agent) => {
      if (typeof agent === 'string') return { id: agent, name: agent }
      const id = agent?.id ?? agent?.agentId ?? agent?.name ?? agent?.agentName ?? ''
      const name = agent?.name ?? agent?.agentName ?? agent?.displayName ?? id
      return { id, name }
    })
    .filter((agent) => agent.id)
}

/** Turn any launch failure into a clear, user-facing message. */
export function describeLaunchError(error) {
  const status = Number(error?.status)
  const code = error?.code
  const detailCode = error?.details?.data?.code ?? error?.details?.code ?? null
  if (status === 400 || code === 'INVALID_RUN_REQUEST') {
    return 'Requête de lancement invalide (INVALID_RUN_REQUEST) : vérifiez le workflow et le namespace.'
  }
  if (isConflictError(error)) {
    return `Conflit : le workflow n'est pas dans un état lançable${detailCode ? ` (${detailCode})` : ''}.`
  }
  if (status >= 500) {
    return `Erreur serveur (${status}) : le lancement a échoué${detailCode ? ` (${detailCode})` : ''}.`
  }
  const message = typeof error?.message === 'string' ? error.message.trim() : ''
  return message ? `Lancement impossible : ${message}` : 'Lancement impossible : erreur réseau ou serveur.'
}

function readField(event) {
  const target = event?.target
  if (!target) return null
  const name = target.name ?? target.dataset?.name
  if (!name) return null
  return { name, value: target.value, checked: target.checked === true }
}

function renderAgents(state) {
  if (!state.namespaceId) {
    return '<p class="placeholder" data-launch-agents-hint="true">Renseignez un namespace pour charger les agents.</p>'
  }
  if (state.agentsLoading) {
    return '<p class="placeholder" data-launch-agents-loading="true">Chargement des agents…</p>'
  }
  if (state.agentsError) {
    return `<p class="placeholder" data-launch-agents-error="true">${esc(state.agentsError)}</p>`
  }
  if (state.agents.length === 0) {
    return '<p class="placeholder" data-launch-agents-empty="true">Aucun agent disponible.</p>'
  }
  return state.agents
    .map((agent) => {
      const checked = state.selectedAgents.includes(agent.id) ? ' checked' : ''
      return (
        `<label class="chip" data-launch-agent="${esc(agent.id)}">` +
        `<input type="checkbox" name="agents" data-agent-id="${esc(agent.id)}" value="${esc(agent.id)}"${checked} /> ` +
        `${esc(agent.name ?? agent.id)}</label>`
      )
    })
    .join('')
}

/** Render the whole view markup from its state. */
export function renderRunLaunch(state) {
  const head =
    '<div class="panel" data-launch-head="true">' +
    '<h2 class="panel-title">Lancer un workflow</h2>' +
    '<p class="placeholder">Sélectionnez une définition, un namespace (FACTORY_ROOT / contexte) puis lancez l\'exécution gouvernée.</p>' +
    '</div>'

  if (state.phase === 'loading') {
    return (
      '<div class="run-launch" data-run-launch="true">' +
      head +
      '<div class="panel" data-launch-loading="true"><p class="placeholder">Chargement des définitions…</p></div></div>'
    )
  }
  if (state.phase === 'error') {
    return (
      '<div class="run-launch" data-run-launch="true">' +
      head +
      '<div class="panel" data-launch-error="true">' +
      '<h2 class="panel-title">Définitions indisponibles</h2>' +
      `<p class="placeholder">${esc(state.error)}</p></div></div>`
    )
  }

  const options = state.definitions
    .map((definition) => {
      const selected = definition.id === state.workflowId ? ' selected' : ''
      return `<option value="${esc(definition.id)}"${selected}>${esc(definition.name)}</option>`
    })
    .join('')

  const submitError = state.submitError
    ? `<div class="panel" data-launch-submit-error="true"><p class="placeholder">${esc(state.submitError)}</p></div>`
    : ''
  const feedback = state.submitSuccess
    ? '<div class="panel" data-launch-submit-success="true"><p class="placeholder">Lancement accepté.</p></div>'
    : ''

  return (
    '<div class="run-launch" data-run-launch="true">' +
    head +
    '<form class="panel" data-launch-form="true" novalidate>' +
    submitError +
    feedback +
    '<div class="form-group">' +
    '<label for="launch-workflow-id">Définition de workflow</label>' +
    `<select id="launch-workflow-id" name="workflowId" data-launch-workflow-id="true" class="mono" required>${options}</select>` +
    '</div>' +
    '<div class="form-group">' +
    '<label for="launch-namespace-id">Namespace (workstream)</label>' +
    `<input id="launch-namespace-id" name="namespaceId" data-launch-namespace-id="true" class="mono" required value="${esc(state.namespaceId)}" />` +
    '</div>' +
    '<div class="form-group">' +
    '<label for="launch-factory-root">FACTORY_ROOT (contexte)</label>' +
    `<input id="launch-factory-root" name="factoryRoot" data-launch-factory-root="true" class="mono" value="${esc(state.factoryRoot)}" />` +
    '</div>' +
    '<div class="form-group">' +
    '<label for="launch-ticket">Ticket Jira (optionnel)</label>' +
    `<input id="launch-ticket" name="ticket" data-launch-ticket="true" class="mono" value="${esc(state.ticket)}" />` +
    '</div>' +
    '<fieldset class="form-group" data-launch-agents="true">' +
    '<legend>Agents</legend>' +
    renderAgents(state) +
    '</fieldset>' +
    '<div class="form-actions">' +
    `<button type="submit" class="btn btn-primary" data-launch-submit="true"${state.submitting ? ' disabled' : ''}>${
      state.submitting ? 'Lancement…' : 'Lancer le workflow'
    }</button>` +
    '<button type="button" class="btn btn-danger" data-launch-reset="true">Réinitialiser</button>' +
    '</div>' +
    '</form></div>'
  )
}

/**
 * Mount the governed run launch view.
 *
 * @param {any} container element-like target (typically `#view-launch`)
 * @param {{
 *   apiClient: { get: Function, post: Function },
 *   onNavigate?: (route: string, params?: object) => void,
 *   namespaceId?: string,
 *   workflowId?: string,
 *   ticket?: string,
 *   factoryRoot?: string,
 *   window?: any,
 *   redirectDelayMs?: number,
 *   setTimeoutFn?: typeof setTimeout,
 *   clearTimeoutFn?: typeof clearTimeout,
 * }} [options]
 * @returns {Promise<{ unmount: () => void, render: () => void, submit: () => Promise<void>,
 *   getState: () => object, isMounted: () => boolean, getPendingTimer: () => any }>}
 */
export async function mountRunLaunchView(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('run-launch.mount requires a container')
  const apiClient = options.apiClient
  if (!apiClient || typeof apiClient.get !== 'function' || typeof apiClient.post !== 'function') {
    throw new TypeError('run-launch.mount requires an apiClient with get() and post() methods')
  }

  const setTimeoutFn = options.setTimeoutFn ?? setTimeout
  const clearTimeoutFn = options.clearTimeoutFn ?? clearTimeout
  const redirectDelayMs = Number.isFinite(options.redirectDelayMs) ? options.redirectDelayMs : 0

  const state = {
    mounted: true,
    phase: 'loading',
    error: null,
    definitions: [],
    agents: [],
    agentsLoading: false,
    agentsError: null,
    namespaceId: options.namespaceId ?? '',
    workflowId: options.workflowId ?? '',
    ticket: options.ticket ?? '',
    factoryRoot: options.factoryRoot ?? '',
    selectedAgents: [],
    submitting: false,
    submitError: null,
    submitSuccess: null,
    abortController: null,
    redirectTimer: null,
    onSubmit: null,
    onChange: null,
    onClick: null,
  }

  const render = () => {
    if (!state.mounted) return
    container.innerHTML = renderRunLaunch(state)
  }

  let agentsRequestId = 0

  const navigate = (workflowId, namespaceId) => {
    if (typeof options.onNavigate === 'function') {
      options.onNavigate(DETAIL_ROUTE, { workflowId, namespaceId })
      return
    }
    const win = options.window ?? globalThis.window
    if (win?.location) win.location.hash = `#${buildDetailHash(workflowId, namespaceId)}`
  }

  const loadDefinitions = async () => {
    state.abortController?.abort?.()
    const controller = typeof AbortController === 'function' ? new AbortController() : null
    state.abortController = controller
    const signal = controller?.signal

    state.phase = 'loading'
    state.error = null
    render()

    let payload
    try {
      payload = await apiClient.get('/api/factory/workflow-definitions', { signal })
    } catch (error) {
      if (!state.mounted || controller !== state.abortController) return
      state.phase = 'error'
      state.error = typeof error?.message === 'string' ? error.message : 'Définitions indisponibles.'
      render()
      return
    }
    if (!state.mounted || controller !== state.abortController) return

    state.definitions = normalizeDefinitions(payload)
    if (!state.workflowId && state.definitions.length > 0) state.workflowId = state.definitions[0].id
    state.phase = 'ready'
    render()
  }

  const loadAgents = async () => {
    if (!state.mounted) return
    if (!state.namespaceId) {
      state.agents = []
      state.agentsError = null
      state.agentsLoading = false
      render()
      return
    }
    const controller = state.abortController
    const requestId = ++agentsRequestId
    state.agentsLoading = true
    state.agentsError = null
    render()
    try {
      const payload = await apiClient.get(`/api/agents?namespaceId=${encodeURIComponent(state.namespaceId)}`, {
        signal: controller?.signal,
      })
      if (!state.mounted || requestId !== agentsRequestId) return
      state.agents = normalizeAgents(payload)
    } catch (error) {
      if (!state.mounted || requestId !== agentsRequestId) return
      state.agents = []
      state.agentsError = isConflictError(error)
        ? 'Agents momentanément indisponibles.'
        : typeof error?.message === 'string'
          ? error.message
          : 'Agents indisponibles.'
    } finally {
      if (state.mounted && requestId === agentsRequestId) {
        state.agentsLoading = false
        render()
      }
    }
  }

  const submit = async () => {
    if (!state.mounted || state.submitting) return
    const workflowId = String(state.workflowId ?? '').trim()
    const namespaceId = String(state.namespaceId ?? '').trim()
    if (!workflowId) {
      state.submitError = 'Sélectionnez une définition de workflow.'
      render()
      return
    }
    if (!namespaceId) {
      state.submitError = 'Le namespace est requis.'
      render()
      return
    }

    state.submitting = true
    state.submitError = null
    state.submitSuccess = null
    render()

    const body = { namespaceId }
    const ticket = String(state.ticket ?? '').trim()
    if (ticket) body.ticket = ticket

    let result
    try {
      result = await apiClient.post(buildRunUrl(workflowId), body, { signal: state.abortController?.signal })
    } catch (error) {
      if (!state.mounted) return
      state.submitting = false
      state.submitError = describeLaunchError(error)
      render()
      return
    }
    if (!state.mounted) return

    state.submitting = false
    state.submitSuccess = result ?? { status: 'ACCEPTED' }
    render()

    if (redirectDelayMs > 0) {
      state.redirectTimer = setTimeoutFn(() => {
        state.redirectTimer = null
        if (state.mounted) navigate(workflowId, namespaceId)
      }, redirectDelayMs)
    } else {
      navigate(workflowId, namespaceId)
    }
  }

  state.onSubmit = (event) => {
    event?.preventDefault?.()
    void submit()
  }

  state.onChange = (event) => {
    const field = readField(event)
    if (!field) return
    if (field.name === 'namespaceId') {
      state.namespaceId = field.value
      void loadAgents()
      return
    }
    if (field.name === 'workflowId') {
      state.workflowId = field.value
      return
    }
    if (field.name === 'ticket') {
      state.ticket = field.value
      return
    }
    if (field.name === 'factoryRoot') {
      state.factoryRoot = field.value
      return
    }
    if (field.name === 'agents') {
      const id = event.target?.dataset?.agentId ?? field.value
      if (!id) return
      if (field.checked) {
        if (!state.selectedAgents.includes(id)) state.selectedAgents.push(id)
      } else {
        state.selectedAgents = state.selectedAgents.filter((entry) => entry !== id)
      }
    }
  }

  state.onInput = (event) => {
    const field = readField(event)
    if (!field) return
    if (field.name in state) state[field.name] = field.value
  }

  state.onClick = (event) => {
    const target = event?.target
    const reset = typeof target?.closest === 'function' ? target.closest('[data-launch-reset]') : null
    if (!reset) return
    state.submitError = null
    state.submitSuccess = null
    state.ticket = ''
    state.selectedAgents = []
    render()
  }

  container.addEventListener?.('submit', state.onSubmit)
  container.addEventListener?.('change', state.onChange)
  container.addEventListener?.('input', state.onInput)
  container.addEventListener?.('click', state.onClick)

  let unmounted = false
  const unmount = () => {
    if (unmounted) return
    unmounted = true
    state.mounted = false
    if (state.redirectTimer !== null) {
      clearTimeoutFn(state.redirectTimer)
      state.redirectTimer = null
    }
    state.abortController?.abort?.()
    state.abortController = null
    if (state.onSubmit) container.removeEventListener?.('submit', state.onSubmit)
    if (state.onChange) container.removeEventListener?.('change', state.onChange)
    if (state.onInput) container.removeEventListener?.('input', state.onInput)
    if (state.onClick) container.removeEventListener?.('click', state.onClick)
    state.onSubmit = null
    state.onChange = null
    state.onInput = null
    state.onClick = null
    container.innerHTML = ''
    state.definitions = []
    state.agents = []
    state.selectedAgents = []
    state.submitError = null
    state.submitSuccess = null
  }

  await loadDefinitions()
  if (state.mounted && state.namespaceId) await loadAgents()

  return {
    unmount,
    render,
    submit,
    reload: () => loadDefinitions(),
    loadAgents,
    getState: () => state,
    isMounted: () => state.mounted,
    getPendingTimer: () => state.redirectTimer,
  }
}

/** Alias matching the cockpit view naming convention. */
export const mount = mountRunLaunchView

export default mountRunLaunchView
