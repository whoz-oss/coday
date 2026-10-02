/**
 * Factory Cockpit — governed workflow run launch view (`#view-launch`).
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * This view is the single entry point to START a governed workflow run. Its
 * only launch authority is the governed frontend route:
 *
 *   - `GET  /api/factory/workflow-definitions`        → selectable definitions (`items`)
 *   - `POST /api/factory/workflows/:workflowId/start` → materialize the governed instance
 *   - `POST /api/factory/workflows/:workflowId/run`   → `{ namespaceId, ticket?, repoRoot? }`
 *
 * The server requires an EXISTING governed instance (`state == "existing"`) before
 * it accepts a run, so a launch is a two-step flow: the view first materializes a
 * fresh instance from the selected definition (`/start`, with a generated unique
 * `workflowId`), then triggers it (`/run`). The legacy JSONL `POST /api/factory/runs`
 * route is NEVER used here. On success the view hands off to the run detail view
 * with the targeted `workflowId` + `namespaceId` (`#/detail?workflowId=…&namespaceId=…`).
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
export const CONTROLLER_REQUEST_MAX = 4000
/** Cockpit route the view transitions to on a successful launch. */
export const DETAIL_ROUTE = '/detail'

/** Governed run route for a workflow id. */
export function buildRunUrl(workflowId) {
  return `/api/factory/workflows/${encodeURIComponent(workflowId)}/run`
}

/** Governed start route for a workflow id (materializes the instance). */
export function buildStartUrl(workflowId) {
  return `/api/factory/workflows/${encodeURIComponent(workflowId)}/start`
}

/** Generate a unique governed instance id for a new launch. */
export function generateWorkflowId() {
  return `wf-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

/** True when a `/start` failure simply means the instance already exists. */
export function isAlreadyStartedError(error) {
  if (!error) return false
  const code = error.code ?? error.details?.code ?? error.details?.data?.code
  return code === 'WORKFLOW_IDENTITY_CONFLICT' || code === 'WORKFLOW_ALREADY_EXISTS'
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

/** Normalize AgentOS namespace listings without exposing their configuration. */
export function normalizeNamespaces(payload) {
  return listOf(payload, ['items', 'namespaces', 'content'])
    .map((namespace) => ({
      id: namespace?.id ?? namespace?.namespaceId ?? '',
      name: namespace?.name ?? namespace?.title ?? namespace?.id ?? namespace?.namespaceId ?? '',
    }))
    .filter((namespace) => namespace.id)
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

/** Render the whole view markup from its state. */
export function renderRunLaunch(state) {
  const head =
    '<div class="panel" data-launch-head="true">' +
    '<h2 class="panel-title">Lancer un workflow</h2>' +
    '<p class="placeholder">Sélectionnez une définition et un namespace AgentOS, puis lancez l\'exécution gouvernée.</p>' +
    '</div>'

  if (state.phase === 'loading') {
    return (
      '<div class="run-launch" data-run-launch="true">' +
      head +
      '<div class="panel" data-launch-loading="true"><p class="placeholder">Chargement des définitions et namespaces…</p></div></div>'
    )
  }
  if (state.phase === 'error') {
    return (
      '<div class="run-launch" data-run-launch="true">' +
      head +
      '<div class="panel" data-launch-error="true">' +
      '<h2 class="panel-title">Données de lancement indisponibles</h2>' +
      `<p class="placeholder">${esc(state.error)}</p></div></div>`
    )
  }

  const options = state.definitions
    .map((definition) => {
      const selected = definition.id === state.workflowId ? ' selected' : ''
      return `<option value="${esc(definition.id)}"${selected}>${esc(definition.name)}</option>`
    })
    .join('')
  const namespaceOptions = state.namespaces
    .map((namespace) => {
      const selected = namespace.id === state.namespaceId ? ' selected' : ''
      return `<option value="${esc(namespace.id)}"${selected}>${esc(namespace.name)}</option>`
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
    `<select id="launch-namespace-id" name="namespaceId" data-launch-namespace-id="true" class="mono" required>` +
    '<option value="">Sélectionner un namespace…</option>' + namespaceOptions + '</select>' +
    (state.namespaces.length === 0 ? '<p class="placeholder" data-launch-namespace-empty="true">Aucun namespace AgentOS disponible.</p>' : '') +
    '</div>' +
    '<div class="form-group">' +
    '<label for="launch-controller-request">Demande de l’ingénieur</label>' +
    `<textarea id="launch-controller-request" name="controllerRequest" data-launch-controller-request="true" required maxlength="${CONTROLLER_REQUEST_MAX}" rows="6" placeholder="Décrivez précisément ce que vous voulez que la Software Factory réalise.">${esc(state.controllerRequest)}</textarea>` +
    `<p class="placeholder">${CONTROLLER_REQUEST_MAX} caractères maximum.</p>` +
    '</div>' +
    '<div class="form-group">' +
    '<label for="launch-factory-root">Racine du dépôt (optionnelle)</label>' +
    '<p class="placeholder">Résolue automatiquement depuis le namespace AgentOS. À renseigner uniquement pour forcer un autre dépôt.</p>' +
    `<input id="launch-factory-root" name="factoryRoot" data-launch-factory-root="true" class="mono" placeholder="Résolution automatique via AgentOS" value="${esc(state.factoryRoot)}" />` +
    '</div>' +
    '<div class="form-group">' +
    '<label for="launch-ticket">Ticket Jira (optionnel)</label>' +
    `<input id="launch-ticket" name="ticket" data-launch-ticket="true" class="mono" value="${esc(state.ticket)}" />` +
    '</div>' +
    '<p class="placeholder" data-launch-agents-from-definition="true">Les agents sont imposés par les responsabilités de la définition du workflow.</p>' +
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
 *   registerTeardown?: (fn: () => void) => void,
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
    namespaces: [],
    namespaceId: options.namespaceId ?? '',
    workflowId: options.workflowId ?? '',
    controllerRequest: options.controllerRequest ?? '',
    ticket: options.ticket ?? '',
    factoryRoot: options.factoryRoot ?? '',
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

    let definitionsPayload
    let namespacesPayload
    try {
      ;[definitionsPayload, namespacesPayload] = await Promise.all([
        apiClient.get('/api/factory/workflow-definitions', { signal }),
        apiClient.get('/api/namespaces', { signal }),
      ])
    } catch (error) {
      if (!state.mounted || controller !== state.abortController) return
      state.phase = 'error'
      state.error = typeof error?.message === 'string' ? error.message : 'Définitions indisponibles.'
      render()
      return
    }
    if (!state.mounted || controller !== state.abortController) return

    state.definitions = normalizeDefinitions(definitionsPayload)
    state.namespaces = normalizeNamespaces(namespacesPayload)
    if (!state.workflowId && state.definitions.length > 0) state.workflowId = state.definitions[0].id
    if (state.namespaceId && !state.namespaces.some((namespace) => namespace.id === state.namespaceId)) state.namespaceId = ''
    state.phase = 'ready'
    render()
  }

  const submit = async () => {
    if (!state.mounted || state.submitting) return
    const workflowType = String(state.workflowId ?? '').trim()
    const namespaceId = String(state.namespaceId ?? '').trim()
    const controllerRequest = String(state.controllerRequest ?? '').trim()
    if (!workflowType) {
      state.submitError = 'Sélectionnez une définition de workflow.'
      render()
      return
    }
    if (!namespaceId) {
      state.submitError = 'Le namespace est requis.'
      render()
      return
    }
    if (!controllerRequest) {
      state.submitError = 'La demande de l’ingénieur est requise.'
      render()
      return
    }
    if (controllerRequest.length > CONTROLLER_REQUEST_MAX) {
      state.submitError = `La demande est limitée à ${CONTROLLER_REQUEST_MAX} caractères.`
      render()
      return
    }

    state.submitting = true
    state.submitError = null
    state.submitSuccess = null
    render()

    const ticket = String(state.ticket ?? '').trim()
    const repoRoot = String(state.factoryRoot ?? '').trim()
    // Each launch materializes a brand new instance; the selected definition id
    // is the `workflowType`, never the instance `workflowId`.
    const workflowId = generateWorkflowId()
    // The launch requests outlive this view: successful `/start` followed by
    // `/run` navigates to the detail route, whose teardown aborts only the
    // view-loading controller. Do not bind these control-plane commands to it.
    // Progress is observed independently through projection SSE.

    // Step 1 — materialize the governed instance from the definition. The run
    // route refuses an absent instance, so the start must come first.
    const startBody = {
      workflow: {
        workflowId,
        workflowType,
        title: `Run ${workflowType}`,
        ...(ticket ? { ticket } : {}),
      },
      execution: {
        namespaceId,
        runtimeId: 'factory-dashboard',
        kind: 'agentos',
        agentId: 'factory-agent',
      },
      controllerRequest,
    }
    try {
      await apiClient.post(buildStartUrl(workflowId), startBody)
    } catch (error) {
      if (!state.mounted) return
      // An already-existing instance is not a launch failure: continue to run it.
      if (!isAlreadyStartedError(error)) {
        state.submitting = false
        state.submitError = describeLaunchError(error)
        render()
        return
      }
    }
    if (!state.mounted) return

    // Step 2 — trigger the run on the now-existing instance.
    const body = { namespaceId }
    if (ticket) body.ticket = ticket
    if (repoRoot) body.repoRoot = repoRoot

    let result
    try {
      result = await apiClient.post(buildRunUrl(workflowId), body)
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
      return
    }
    if (field.name === 'workflowId') {
      state.workflowId = field.value
      return
    }
    if (field.name === 'controllerRequest') {
      state.controllerRequest = field.value
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
    state.controllerRequest = ''
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
    state.namespaces = []
    state.submitError = null
    state.submitSuccess = null
  }

  // Let the host (cockpit router) own the teardown so navigating away cleans up
  // the delegated listeners and any pending redirect timer.
  if (typeof options.registerTeardown === 'function') options.registerTeardown(unmount)

  await loadDefinitions()

  return {
    unmount,
    render,
    submit,
    reload: () => loadDefinitions(),
    getState: () => state,
    isMounted: () => state.mounted,
    getPendingTimer: () => state.redirectTimer,
  }
}

/** Alias matching the cockpit view naming convention. */
export const mount = mountRunLaunchView

export default mountRunLaunchView
