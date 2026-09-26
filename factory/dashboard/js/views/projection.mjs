/**
 * Factory Cockpit — governed workflow projection view (Milestone D, Wave 2).
 *
 * Vanilla ESM, zero dependencies, zero build step. Populates the
 * `#view-projection` container with the workstream-scoped workflow list,
 * grouped by case/ticket with collapsible sub-cases, driven by the governed
 * Workflow Projection v2 authority and reactive SSE invalidation.
 *
 * Consumes (never redefines) the shared services:
 *   - GET /api/config                              → `agentosUrl`
 *   - GET /api/factory/workflows?namespaceId=&state=active|removed
 *   - GET /api/factory/workflows/:id             → targeted re-fetch
 *   - POST   /api/factory/workflows/:id/restore
 *   - DELETE /api/factory/workflows/:id
 *   - DELETE /api/factory/workflows/:id/purge
 *   - SSE /api/factory/workflows/stream?namespaceId=  (named projection events)
 *
 * The module is import-safe in Node: no `window`/`document` access happens at
 * module evaluation, only inside {@link mountProjectionView}.
 */

import { SseClient } from '../services/sse-client.mjs'
import { renderWorkflowCard } from '../components/workflow-card.mjs'

export const FALLBACK_GROUP_KEY = '__ungrouped__'
export const FALLBACK_GROUP_LABEL = 'Autres workflows'

export const SSE_EVENT_NAMES = Object.freeze([
  'workflow-projection-updated',
  'workflow-projection-removed',
  'workflow-projection-restored',
  'workflow-projection-purged',
])

export const CONFLICT_CODES = Object.freeze(['REVISION_CONFLICT', 'WORKFLOW_REMOVED', 'INVALID_LIFECYCLE_TRANSITION'])

export const ACTION_META = Object.freeze({
  restore: { label: 'Restaurer le workflow', warning: 'Le workflow reviendra dans la liste active.' },
  remove: { label: 'Supprimer le workflow', warning: 'Le workflow passera dans la corbeille (restauration possible).' },
  purge: { label: 'Purger le workflow', warning: 'Suppression définitive et irréversible.' },
})

/** Escape a value for safe HTML interpolation. Local helper (file-scoped). */
function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Normalize an error into a user-facing message. */
function errorMessage(error) {
  if (!error) return 'Erreur inconnue.'
  if (typeof error.message === 'string' && error.message.trim()) return error.message.trim()
  return 'Action impossible.'
}

/** True when the error is a lifecycle/revision conflict. */
export function isConflictError(error) {
  if (!error) return false
  if (error.status === 409) return true
  if (typeof error.code === 'string' && CONFLICT_CODES.includes(error.code)) return true
  return false
}

/** Extract stored relations from a snapshot (v2 `instance` wins over v1). */
export function workflowRelations(snapshot) {
  return snapshot?.instance?.relations ?? snapshot?.relations ?? {}
}

/** Derive the case/ticket grouping key of a workflow snapshot. */
export function deriveGroupKey(snapshot) {
  const caseId = snapshot?.controllerExecution?.caseId
  if (typeof caseId === 'string' && caseId) return caseId
  const relations = workflowRelations(snapshot)
  if (typeof relations.rootWorkflowId === 'string' && relations.rootWorkflowId) return relations.rootWorkflowId
  return FALLBACK_GROUP_KEY
}

/** Human label for a grouping key. */
export function deriveGroupLabel(key) {
  return key === FALLBACK_GROUP_KEY ? FALLBACK_GROUP_LABEL : key
}

/** Parent workflow id declared by relations, or `null`. */
export function parentWorkflowId(snapshot) {
  const relations = workflowRelations(snapshot)
  return typeof relations.parentWorkflowId === 'string' && relations.parentWorkflowId
    ? relations.parentWorkflowId
    : null
}

/** Resolve the workflow id of a snapshot/item. */
export function snapshotWorkflowId(snapshot) {
  return snapshot?.workflowId ?? snapshot?.projection?.workflowId ?? null
}

/**
 * Group workflow snapshots by case/ticket and nest sub-cases by parent relation.
 *
 * @param {any[]} snapshots
 * @returns {Array<{ key: string, label: string, roots: any[], nodes: any[] }>}
 */
export function groupWorkflows(snapshots = []) {
  const items = Array.isArray(snapshots) ? snapshots : []
  const groups = new Map()

  for (const snapshot of items) {
    const id = snapshotWorkflowId(snapshot)
    if (!id) continue
    const key = deriveGroupKey(snapshot)
    if (!groups.has(key)) groups.set(key, { key, label: deriveGroupLabel(key), nodes: [], byId: new Map() })
    const group = groups.get(key)
    const node = { workflowId: id, snapshot, children: [] }
    group.nodes.push(node)
    group.byId.set(id, node)
  }

  for (const group of groups.values()) {
    const roots = []
    for (const node of group.nodes) {
      const parent = parentWorkflowId(node.snapshot)
      const parentNode = parent ? group.byId.get(parent) : null
      if (parentNode && parentNode !== node) parentNode.children.push(node)
      else roots.push(node)
    }

    // Promote nodes trapped in a parent cycle so no workflow is ever dropped.
    const reachable = new Set()
    const walk = (node) => {
      if (!node || reachable.has(node.workflowId)) return
      reachable.add(node.workflowId)
      for (const child of node.children) walk(child)
    }
    for (const root of roots) walk(root)
    for (const node of group.nodes) {
      if (reachable.has(node.workflowId)) continue
      const parent = parentWorkflowId(node.snapshot)
      const parentNode = parent ? group.byId.get(parent) : null
      if (parentNode) parentNode.children = parentNode.children.filter((child) => child !== node)
      roots.push(node)
      walk(node)
    }

    group.roots = roots
    delete group.byId
  }

  return [...groups.values()].sort((a, b) => {
    if (a.key === FALLBACK_GROUP_KEY) return 1
    if (b.key === FALLBACK_GROUP_KEY) return -1
    return a.key.localeCompare(b.key)
  })
}

/**
 * Stateful, DOM-free projection controller. Owns list state, grouping, SSE
 * invalidation handling and lifecycle actions so the view (and the offline test
 * suite) share one implementation.
 */
export class ProjectionController {
  constructor(options = {}) {
    this.api = options.api ?? null
    this.namespaceId = options.namespaceId ?? null
    this.mode = options.mode === 'removed' ? 'removed' : 'active'
    this.agentosUrl = typeof options.agentosUrl === 'string' && options.agentosUrl ? options.agentosUrl : null
    this.codayExpressUrl =
      typeof options.codayExpressUrl === 'string' && options.codayExpressUrl ? options.codayExpressUrl : null
    // Explicit per-base getters keep their historical contract; when none is
    // supplied, both trusted bases are resolved from ONE `/api/config` fetch.
    this.getAgentosUrl = typeof options.getAgentosUrl === 'function' ? options.getAgentosUrl : null
    this.getCodayExpressUrl = typeof options.getCodayExpressUrl === 'function' ? options.getCodayExpressUrl : null
    this.getConfig =
      typeof options.getConfig === 'function'
        ? options.getConfig
        : this.api?.get && !this.getAgentosUrl && !this.getCodayExpressUrl
          ? () => this.api.get('/api/config')
          : null
    this.onChange = typeof options.onChange === 'function' ? options.onChange : () => {}
    this.onError = typeof options.onError === 'function' ? options.onError : () => {}
    this.onNotice = typeof options.onNotice === 'function' ? options.onNotice : () => {}
    this.confirm = typeof options.confirm === 'function' ? options.confirm : null

    this.SseClientImpl = options.SseClient ?? SseClient
    this.sse = options.sse ?? null
    this.streamUrl =
      options.streamUrl ??
      (this.namespaceId
        ? `/api/factory/workflows/stream?namespaceId=${encodeURIComponent(this.namespaceId)}`
        : null)
    this.ownsSse = false
    this.unsubscribers = []
    this.pending = new Set()

    this.active = new Map()
    this.removed = new Map()
    this.loading = false
    this.lastError = null
    this.disposed = false
  }

  /** Workflows currently displayed for the active mode. */
  get workflows() {
    return this.mode === 'removed' ? [...this.removed.values()] : [...this.active.values()]
  }

  /** Grouped view of the current mode. */
  getGroups() {
    return groupWorkflows(this.workflows)
  }

  /** Serialize observable state for rendering and assertions. */
  getState() {
    return {
      mode: this.mode,
      loading: this.loading,
      agentosUrl: this.agentosUrl,
      codayExpressUrl: this.codayExpressUrl,
      active: [...this.active.values()],
      removed: [...this.removed.values()],
      groups: this.getGroups(),
      lastError: this.lastError,
      sseListeners: this.unsubscribers.length,
    }
  }

  listPath(state = this.mode) {
    const namespace = encodeURIComponent(this.namespaceId ?? '')
    return `/api/factory/workflows?namespaceId=${namespace}&state=${state === 'removed' ? 'removed' : 'active'}`
  }

  detailPath(workflowId) {
    return `/api/factory/workflows/${encodeURIComponent(workflowId)}?namespaceId=${encodeURIComponent(this.namespaceId ?? '')}`
  }

  lifecyclePath(action, workflowId) {
    const base = `/api/factory/workflows/${encodeURIComponent(workflowId)}`
    const namespace = encodeURIComponent(this.namespaceId ?? '')
    if (action === 'restore') return `${base}/restore?namespaceId=${namespace}`
    if (action === 'purge') return `${base}/purge?namespaceId=${namespace}`
    return `${base}?namespaceId=${namespace}`
  }

  /** Track a fire-and-forget task so teardown can drop pending references. */
  runDetached(fn) {
    if (this.disposed) return Promise.resolve()
    const task = Promise.resolve()
      .then(fn)
      .catch((error) => {
        if (!this.disposed) {
          this.lastError = error
          this.onError(errorMessage(error), error)
        }
      })
      .finally(() => {
        this.pending.delete(task)
      })
    this.pending.add(task)
    return task
  }

  /** Resolve the trusted AgentOS/Coday Express bases, caching the first success. */
  async resolveAgentosUrl() {
    if (this.agentosUrl && this.codayExpressUrl) return this.agentosUrl

    if (this.getConfig) {
      try {
        const config = await this.getConfig()
        const agentos = config?.agentosUrl
        if (!this.agentosUrl && typeof agentos === 'string' && agentos.trim()) this.agentosUrl = agentos
        const express = config?.codayExpressUrl
        if (!this.codayExpressUrl && typeof express === 'string' && express.trim()) this.codayExpressUrl = express
        if (this.agentosUrl || this.codayExpressUrl) return this.agentosUrl
      } catch {
        // A missing config must never break the view; links simply stay plain text.
      }
    }

    if (!this.agentosUrl && this.getAgentosUrl) {
      try {
        const value = await this.getAgentosUrl()
        if (typeof value === 'string' && value.trim()) this.agentosUrl = value
      } catch {
        // A missing config must never break the view; links simply stay plain text.
      }
    }
    if (!this.codayExpressUrl && this.getCodayExpressUrl) {
      try {
        const value = await this.getCodayExpressUrl()
        if (typeof value === 'string' && value.trim()) this.codayExpressUrl = value
      } catch {
        // A missing Coday Express base keeps threads unclickable.
      }
    }
    return this.agentosUrl
  }

  /** Attach the named SSE listeners exactly once. */
  attachSse() {
    if (this.disposed || this.unsubscribers.length) return
    if (!this.sse && this.SseClientImpl && this.streamUrl) {
      this.sse = new this.SseClientImpl(this.streamUrl)
      this.ownsSse = true
      this.sse.connect?.()
    }
    if (!this.sse?.on) return
    for (const event of SSE_EVENT_NAMES) {
      const unsubscribe = this.sse.on(event, (data) => this.handleSseEvent(event, data))
      if (typeof unsubscribe === 'function') this.unsubscribers.push(unsubscribe)
    }
  }

  /** Fetch the list for a given state and replace local maps. */
  async load(state = this.mode) {
    if (this.disposed || !this.api?.get) return
    this.loading = true
    this.onChange()
    try {
      const payload = await this.api.get(this.listPath(state))
      if (this.disposed) return
      const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : []
      const target = new Map()
      for (const item of items) {
        const id = snapshotWorkflowId(item)
        if (id) target.set(id, item)
      }
      if (state === 'removed') this.removed = target
      else this.active = target
      this.lastError = null
    } catch (error) {
      if (!this.disposed) {
        this.lastError = error
        this.onError(errorMessage(error), error)
      }
    } finally {
      this.loading = false
      if (!this.disposed) this.onChange()
    }
  }

  /** Switch between the active and removed lists. */
  async setMode(mode) {
    const next = mode === 'removed' ? 'removed' : 'active'
    if (next === this.mode) return
    this.mode = next
    this.onChange()
    await this.load(next)
  }

  /** Fetch the trusted timing summary for a workflow (GET /timing). */
  async fetchTiming(workflowId) {
    if (this.disposed || !this.api?.get || !workflowId) return null
    const namespace = encodeURIComponent(this.namespaceId ?? '')
    try {
      const payload = await this.api.get(
        `/api/factory/workflows/${encodeURIComponent(workflowId)}/timing?namespaceId=${namespace}`,
      )
      return payload?.timing ?? null
    } catch (error) {
      if (!this.disposed) this.onError(errorMessage(error), error)
      return null
    }
  }

  /** Targeted re-fetch of a single workflow and merge into local state. */
  async refreshWorkflow(workflowId) {
    if (this.disposed || !workflowId || !this.api?.get) return
    const detail = await this.api.get(this.detailPath(workflowId))
    if (this.disposed) return
    const state = detail?.state
    if (state === 'existing' && detail?.projection) {
      const snapshot = { ...detail, workflowId: detail.workflowId ?? workflowId }
      this.removed.delete(workflowId)
      this.active.set(workflowId, snapshot)
    } else if (state === 'removed') {
      const known = this.active.get(workflowId) ?? this.removed.get(workflowId) ?? { workflowId }
      this.active.delete(workflowId)
      this.removed.set(workflowId, { ...known, workflowId, state: 'removed' })
    } else {
      this.active.delete(workflowId)
      this.removed.delete(workflowId)
    }
    this.onChange()
  }

  /** Move a workflow to the removed map locally (best-effort, no fetch needed). */
  applyRemoved(workflowId) {
    const known = this.active.get(workflowId)
    this.active.delete(workflowId)
    if (known) this.removed.set(workflowId, { ...known, state: 'removed' })
    else if (this.mode === 'removed') this.runDetached(() => this.load('removed'))
    this.onChange()
  }

  /** Drop a workflow from both maps after a purge. */
  applyPurged(workflowId) {
    this.active.delete(workflowId)
    this.removed.delete(workflowId)
    this.onChange()
  }

  /** React to a named projection SSE event. */
  handleSseEvent(event, payload) {
    if (this.disposed) return
    const workflowId = payload && typeof payload === 'object' ? payload.workflowId : null
    switch (event) {
      case 'workflow-projection-updated':
      case 'workflow-projection-restored':
        if (workflowId) this.runDetached(() => this.refreshWorkflow(workflowId))
        else this.runDetached(() => this.load(this.mode))
        break
      case 'workflow-projection-removed':
        if (workflowId) this.applyRemoved(workflowId)
        else this.runDetached(() => this.load(this.mode))
        break
      case 'workflow-projection-purged':
        if (workflowId) this.applyPurged(workflowId)
        else {
          this.active.clear()
          this.removed.clear()
          this.onChange()
        }
        break
      default:
        break
    }
  }

  /** Execute a lifecycle action, mapping conflicts to structured feedback. */
  async performLifecycle(action, workflowId) {
    if (!ACTION_META[action]) return { ok: false, action, workflowId, message: 'Action inconnue.' }
    if (!this.api) return { ok: false, action, workflowId, message: 'Client API indisponible.' }
    try {
      const path = this.lifecyclePath(action, workflowId)
      if (action === 'restore') await this.api.post(path, {})
      else await this.api.delete(path)
      await this.load(this.mode)
      this.onNotice?.(`${ACTION_META[action].label} : succès.`)
      return { ok: true, action, workflowId, state: action === 'restore' ? 'active' : action === 'remove' ? 'removed' : 'purged' }
    } catch (error) {
      const conflict = isConflictError(error)
      const message = conflict
        ? `Conflit de révision (${error?.code ?? 'REVISION_CONFLICT'}) : rechargement de l'état courant.`
        : errorMessage(error)
      this.lastError = error
      this.onError(message, error)
      await this.load(this.mode).catch(() => {})
      return {
        ok: false,
        action,
        workflowId,
        conflict,
        status: error?.status ?? null,
        code: error?.code ?? null,
        message,
      }
    }
  }

  /** Confirmed lifecycle action (uses the injected dialog confirm when present). */
  async requestAction(action, workflowId) {
    if (!ACTION_META[action]) return { ok: false, action, workflowId, message: 'Action inconnue.' }
    if (this.confirm) {
      let approved = false
      try {
        approved = await this.confirm({ action, workflowId, label: ACTION_META[action].label, warning: ACTION_META[action].warning })
      } catch {
        approved = false
      }
      if (!approved) return { ok: false, cancelled: true, action, workflowId }
    }
    return this.performLifecycle(action, workflowId)
  }

  /** Resolve config, subscribe to SSE and perform the initial load. */
  async init() {
    if (this.disposed) return
    await this.resolveAgentosUrl()
    this.attachSse()
    await this.load(this.mode)
  }

  /** Idempotent teardown: unsubscribe SSE, close owned client, drop pending refs. */
  teardown() {
    if (this.disposed) return
    this.disposed = true
    for (const unsubscribe of this.unsubscribers) {
      try {
        unsubscribe()
      } catch {
        // A faulty unsubscribe must never block teardown.
      }
    }
    this.unsubscribers = []
    if (this.ownsSse && typeof this.sse?.close === 'function') {
      try {
        this.sse.close()
      } catch {
        // close() must stay idempotent.
      }
    }
    this.sse = null
    this.pending.clear()
  }
}

/** Factory for {@link ProjectionController}. */
export function createProjectionController(options = {}) {
  return new ProjectionController(options)
}

/** Render a single workflow node (card + collapsible sub-cases). */
function renderNode(node, state, visited, depth = 0) {
  if (!node?.workflowId || visited.has(node.workflowId)) return ''
  visited.add(node.workflowId)
  const card = renderWorkflowCard(node.snapshot, {
    agentosUrl: state.agentosUrl,
    codayExpressUrl: state.codayExpressUrl,
    mode: state.mode,
  })
  const children = (node.children ?? [])
    .map((child) => renderNode(child, state, visited, depth + 1))
    .filter(Boolean)
    .join('')
  const subcases = children
    ? `<details class="workflow-subcases" data-subcase-count="${(node.children ?? []).length}">` +
      `<summary>Sous-cas (${(node.children ?? []).length})</summary>` +
      `<div class="workflow-subcases-body">${children}</div>` +
      `</details>`
    : ''
  return `<div class="workflow-node" data-depth="${depth}">${card}${subcases}</div>`
}

/** Render a case/ticket group as a collapsible `<details>` block. */
export function renderWorkflowGroup(group, state) {
  const visited = new Set()
  const body = (group.roots ?? [])
    .map((node) => renderNode(node, state, visited))
    .filter(Boolean)
    .join('')
  return (
    `<details class="projection-group" open data-group-key="${escapeHtml(group.key)}">` +
    `<summary class="projection-group-summary">` +
    `<span class="projection-group-label">${escapeHtml(group.label)}</span>` +
    `<span class="chip projection-group-count">${(group.nodes ?? []).length}</span>` +
    `</summary>` +
    `<div class="projection-group-body">${body}</div>` +
    `</details>`
  )
}

/** Render the full projection view (toolbar + groups) as an HTML string. */
export function renderProjection(controller) {
  const state = controller.getState()
  const activeClass = state.mode === 'active' ? 'btn btn-primary' : 'btn'
  const removedClass = state.mode === 'removed' ? 'btn btn-primary' : 'btn'
  const tabs =
    `<div class="projection-toolbar">` +
    `<button type="button" class="${activeClass}" data-projection-mode="active">Actifs</button>` +
    `<button type="button" class="${removedClass}" data-projection-mode="removed">Supprimés</button>` +
    `<span class="projection-count">${state.groups.length} groupe(s)</span>` +
    (state.loading ? `<span class="chip chip-running">chargement</span>` : '') +
    `</div>`
  const body = state.groups.length
    ? state.groups.map((group) => renderWorkflowGroup(group, state)).join('')
    : `<p class="placeholder">Aucun workflow ${state.mode === 'removed' ? 'supprimé' : 'actif'}.</p>`
  return `<div class="projection-view" data-mode="${escapeHtml(state.mode)}">${tabs}<div class="projection-groups">${body}</div></div>`
}

/** Build the default native-`<dialog>` confirmation flow. */
export function createDialogConfirm({ doc, showModal, closeModal } = {}) {
  return ({ action, workflowId, label, warning }) =>
    new Promise((resolve) => {
      if (!doc) {
        resolve(false)
        return
      }
      const host = doc.getElementById('cockpit-dialog-content')
      const dialog = doc.getElementById('cockpit-dialog')
      if (!host || !dialog) {
        resolve(false)
        return
      }
      const content =
        `<div class="dialog-body"><h3>${escapeHtml(label)}</h3>` +
        `<p>${escapeHtml(warning)}</p>` +
        `<code class="cockpit-id">${escapeHtml(workflowId)}</code></div>` +
        `<div class="dialog-actions">` +
        `<button type="button" data-dialog-action="cancel">Annuler</button>` +
        `<button type="button" class="${action === 'restore' ? 'btn-primary' : 'btn-danger'}" data-dialog-action="confirm">Confirmer</button>` +
        `</div>`

      let settled = false
      const finish = (value) => {
        if (settled) return
        settled = true
        host.removeEventListener?.('click', onHostClick)
        try {
          if (typeof closeModal === 'function') closeModal()
          else dialog.close?.()
        } catch {
          // Closing an already-closed dialog is a no-op.
        }
        resolve(value)
      }
      const onHostClick = (event) => {
        const button = event?.target?.closest?.('[data-dialog-action]')
        if (!button) return
        finish(button.dataset?.dialogAction === 'confirm')
      }

      if (typeof showModal === 'function') showModal(content)
      else {
        host.innerHTML = content
        dialog.showModal?.()
      }
      host.addEventListener('click', onHostClick)
    })
}

/**
 * Mount the projection view into a container.
 *
 * @param {object} container DOM element (or a compatible double)
 * @param {object} [options] `{ api, sse, SseClient, namespaceId, getAgentosUrl,
 *   getCodayExpressUrl, getConfig, agentosUrl, codayExpressUrl,
 *   registerTeardown, confirm, document, showModal, closeModal, onChange, onError }`
 * @returns {{ controller: ProjectionController, teardown: Function, ready: Promise<void>, render: Function }}
 */
export function mountProjectionView(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('mountProjectionView requires a container')
  const doc = options.document ?? globalThis.document ?? null
  const confirm =
    typeof options.confirm === 'function'
      ? options.confirm
      : createDialogConfirm({ doc, showModal: options.showModal, closeModal: options.closeModal })

  let disposed = false
  const render = () => {
    if (!disposed) container.innerHTML = renderProjection(controller)
  }

  const controller = new ProjectionController({
    ...options,
    confirm,
    onChange: () => {
      render()
      options.onChange?.()
    },
    onError: (message, error) => {
      render()
      options.onError?.(message, error)
    },
  })

  const onClick = (event) => {
    const target = event?.target
    const modeButton = target?.closest?.('[data-projection-mode]')
    if (modeButton) {
      void controller.setMode(modeButton.dataset?.projectionMode)
      return
    }
    const actionButton = target?.closest?.('[data-action]')
    if (!actionButton) return
    const action = actionButton.dataset?.action
    const workflowId = actionButton.dataset?.workflowId
    if (!action || !workflowId) return
    void controller.requestAction(action, workflowId)
  }

  container.addEventListener('click', onClick)

  const teardown = () => {
    if (disposed) return
    disposed = true
    container.removeEventListener('click', onClick)
    controller.teardown()
  }

  if (typeof options.registerTeardown === 'function') options.registerTeardown(teardown)

  const ready = controller.init()
  render()

  return { controller, teardown, ready, render }
}

export default mountProjectionView
