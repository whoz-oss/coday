/**
 * Factory Cockpit — Forge agent activity feed (real-time SSE).
 *
 * Vanilla ESM, zero dependencies, zero build step. Reconstructs the Angular
 * `ForgeActivityPanelComponent` + `ForgeActivityService` in the vanilla cockpit:
 * it opens a Server-Sent Events connection on `GET /api/cases/:caseId/events`,
 * folds the named AgentOS events into a display model, and renders a live feed
 * with Dockyard styling.
 *
 * Lifecycle is leak-free by construction: `disconnect()` (aliased `close()`)
 * detaches every SSE listener, clears the reconnect timer and closes the
 * underlying `EventSource`. Both are idempotent.
 *
 * The module is import-safe in Node: nothing touches `window`, `document` or
 * `EventSource` at evaluation time. Tests inject a mock `EventSource`.
 */

import { SseClient } from '../services/sse-client.mjs'

/** Named AgentOS case events the feed subscribes to. */
export const FORGE_ACTIVITY_EVENT_NAMES = Object.freeze([
  'MessageEvent',
  'CaseStatusEvent',
  'AgentSelectedEvent',
  'AgentRunningEvent',
  'AgentFinishedEvent',
  'ThinkingEvent',
  'TextChunkEvent',
  'ToolRequestEvent',
  'ToolResponseEvent',
  'IntentionGeneratedEvent',
  'WarnEvent',
  'ErrorEvent',
  'QuestionEvent',
  'AnswerEvent',
])

/** Case statuses the feed understands, with their French labels. */
export const CASE_STATUS_LABELS = Object.freeze({
  RUNNING: 'En cours',
  IDLE: 'En attente',
  PENDING: 'Démarrage',
  KILLED: 'Arrêté',
  ERROR: 'Erreur',
})

/** Terminal statuses that close the SSE connection. */
export const TERMINAL_CASE_STATUSES = Object.freeze(['KILLED', 'ERROR'])

const DEFAULT_MAX_EVENTS = 500
const MESSAGE_TRUNCATE = 200

/** Escape a value for safe HTML text interpolation. */
export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Human label for a case status. */
export function statusLabel(status) {
  return CASE_STATUS_LABELS[status] ?? String(status ?? '')
}

function truncate(value, max = MESSAGE_TRUNCATE) {
  const text = String(value ?? '')
  return text.length <= max ? text : `${text.slice(0, max)}…`
}

/** Concatenate the textual parts of a case `MessageEvent`. */
export function messageText(event) {
  return (event?.content ?? [])
    .filter((part) => part && typeof part.content === 'string')
    .map((part) => part.content)
    .join('')
}

/** Human duration for a tool response, matching the Angular panel. */
export function toolDuration(ms) {
  if (ms === null || ms === undefined) return null
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

/**
 * Fold a list of AgentOS case events into the activity feed model.
 * Mirrors the Angular `feedItems` computed: unknown event types are ignored.
 *
 * @param {Array<object>} events
 * @returns {Array<{ id: string, kind: string, role?: string, text?: string, toolName?: string, duration?: string|null }>}
 */
export function buildFeedItems(events = []) {
  const items = []
  for (const event of Array.isArray(events) ? events : []) {
    if (!event || typeof event !== 'object') continue
    switch (event.type) {
      case 'MessageEvent':
        items.push({
          id: event.id,
          kind: 'message',
          role: event.actor?.role === 'AGENT' ? (event.actor?.displayName ?? 'Agent') : 'User',
          text: truncate(messageText(event)),
        })
        break
      case 'ToolRequestEvent':
        items.push({ id: event.id, kind: 'tool', toolName: event.toolName ?? 'tool', duration: null })
        break
      case 'ToolResponseEvent':
        items.push({
          id: event.id,
          kind: 'tool',
          toolName: event.toolName ?? 'tool',
          duration: toolDuration(event.durationMs),
        })
        break
      case 'IntentionGeneratedEvent':
        items.push({
          id: event.id,
          kind: 'intention',
          text: `${event.toolName ?? ''}${event.intention ? `: ${String(event.intention).slice(0, 100)}` : ''}`,
        })
        break
      case 'AgentSelectedEvent':
      case 'AgentRunningEvent':
        items.push({ id: event.id, kind: 'status', text: `Agent: ${event.agentName ?? ''}` })
        break
      case 'ErrorEvent':
      case 'WarnEvent':
        items.push({
          id: event.id,
          kind: event.type === 'ErrorEvent' ? 'error' : 'warn',
          text: truncate(event.message),
        })
        break
      default:
        break
    }
  }
  return items
}

function renderFeedItem(item) {
  const kind = esc(item.kind ?? 'event')
  const head = item.role ? `<span class="feed-role">${esc(item.role)}</span> ` : ''
  const tool = item.toolName ? `<span class="feed-tool">${esc(item.toolName)}</span>` : ''
  const duration = item.duration ? `<span class="chip feed-duration">${esc(item.duration)}</span>` : ''
  const text = item.text ? `<span class="feed-text">${esc(item.text)}</span>` : ''
  return (
    `<li class="feed-item" data-feed-kind="${kind}">` +
    `<span class="chip feed-kind feed-kind--${kind}">${kind}</span>` +
    `${head}${tool}${duration}${text}</li>`
  )
}

/**
 * Render the activity panel as an escaped HTML string.
 *
 * @param {{
 *   events?: Array<object>,
 *   streamingText?: string,
 *   caseStatus?: string,
 *   connected?: boolean,
 *   title?: string,
 *   caseId?: string|null,
 * }} [options]
 * @returns {string}
 */
export function renderForgeActivity(options = {}) {
  const {
    events = [],
    streamingText = '',
    caseStatus = 'IDLE',
    connected = false,
    title = "Activité de l'agent",
    caseId = null,
  } = options

  const items = buildFeedItems(events)
  const feed = items.length
    ? `<ul class="forge-feed" data-feed-count="${items.length}">${items.map(renderFeedItem).join('')}</ul>`
    : '<p class="placeholder">Aucun événement pour le moment.</p>'

  const streamBlock = streamingText
    ? `<pre class="forge-streaming" data-streaming="true">${esc(streamingText)}</pre>`
    : ''

  const statusWord = statusLabel(caseStatus)
  const connectionChip = connected
    ? '<span class="chip chip-running" data-activity-connection="connected">connecté</span>'
    : '<span class="chip" data-activity-connection="disconnected">déconnecté</span>'

  return (
    '<section class="forge-activity-panel" data-forge-activity="true">' +
    '<header class="forge-activity-head">' +
    `<h3 class="panel-title" style="margin:0">${esc(title)}</h3>` +
    `<span class="chip status-badge" data-activity-status="${esc(caseStatus)}">${esc(statusWord)}</span>` +
    connectionChip +
    (caseId ? `<code class="cockpit-id">${esc(caseId)}</code>` : '') +
    '<button type="button" class="btn" data-activity-action="disconnect">Déconnecter</button>' +
    '</header>' +
    streamBlock +
    feed +
    '</section>'
  )
}

/**
 * Stateful, DOM-free activity controller. Owns the SSE connection, the folded
 * event list and the streaming buffer so the view and the offline test suite
 * share one implementation.
 */
export class ForgeActivityStream {
  constructor(options = {}) {
    this.SseClientImpl = options.SseClient ?? SseClient
    this.EventSourceImpl = options.EventSource ?? null
    this.basePath = typeof options.basePath === 'string' ? options.basePath : ''
    this.maxEvents = Number.isFinite(options.maxEvents) ? options.maxEvents : DEFAULT_MAX_EVENTS
    this.onChange = typeof options.onChange === 'function' ? options.onChange : () => {}

    this.sse = options.sse ?? null
    this.ownsSse = false
    this.unsubscribers = []
    this.activeCaseId = null
    this.events = []
    this.caseStatus = 'IDLE'
    this.streamingText = ''
    this.connected = false
    this.disposed = false
  }

  /** Build the SSE URL for a case, honouring the configured base path. */
  eventsUrl(caseId) {
    const base = String(this.basePath ?? '').replace(/\/+$/, '')
    return `${base}/api/cases/${encodeURIComponent(caseId)}/events`
  }

  /**
   * Open (or reuse) the SSE connection for `caseId`.
   * Reconnecting to a different case tears the previous connection down first.
   *
   * @param {string} caseId
   * @param {{ basePath?: string, reconnectDelayMs?: number }} [options]
   * @returns {this}
   */
  connect(caseId, options = {}) {
    if (this.disposed || !caseId) return this
    const id = String(caseId)
    if (this.activeCaseId === id && this.connected && this.sse) return this

    // Drop any previous connection without clearing the disposed flag.
    this.closeConnection()

    this.activeCaseId = id
    this.events = []
    this.caseStatus = 'PENDING'
    this.streamingText = ''
    this.connected = true

    const basePath = typeof options.basePath === 'string' ? options.basePath : this.basePath
    const previousBase = this.basePath
    this.basePath = basePath
    const url = this.eventsUrl(id)
    this.basePath = previousBase

    const sseOptions = {}
    if (Number.isFinite(options.reconnectDelayMs)) sseOptions.reconnectDelayMs = options.reconnectDelayMs
    if (this.EventSourceImpl) sseOptions.EventSource = this.EventSourceImpl

    const sse = new this.SseClientImpl(url, sseOptions)
    this.sse = sse
    this.ownsSse = true

    const unsubscribers = []
    for (const name of FORGE_ACTIVITY_EVENT_NAMES) {
      const unsubscribe = sse.on(name, (data) => this.handleEvent(data))
      if (typeof unsubscribe === 'function') unsubscribers.push(unsubscribe)
    }
    unsubscribers.push(
      sse.on('open', () => {
        this.connected = true
        this.emitChange()
      })
    )
    unsubscribers.push(
      sse.on('error', () => {
        this.connected = false
        this.emitChange()
      })
    )
    this.unsubscribers = unsubscribers

    sse.connect()
    this.emitChange()
    return this
  }

  /** Fold one parsed case event into the feed state. */
  handleEvent(event) {
    if (this.disposed || !event || typeof event !== 'object') return
    const type = event.type

    if (type === 'TextChunkEvent') {
      const chunk = event.chunk
      if (typeof chunk === 'string' && chunk) this.streamingText += chunk
      this.emitChange()
      return
    }

    if (type === 'AgentFinishedEvent') this.streamingText = ''

    if (type === 'CaseStatusEvent') {
      const status = event.status
      if (status) this.caseStatus = status
      if (TERMINAL_CASE_STATUSES.includes(status)) {
        this.closeConnection()
        this.connected = false
      }
    }

    const id = event.id
    const isDuplicate = id !== undefined && id !== null && this.events.some((existing) => existing.id === id)
    if (!isDuplicate) {
      this.events.push(event)
      if (this.events.length > this.maxEvents) this.events.splice(0, this.events.length - this.maxEvents)
    }
    this.emitChange()
  }

  emitChange() {
    this.onChange?.(this.getState())
  }

  /** Close the EventSource and detach every listener, keeping the folded state. */
  closeConnection() {
    for (const unsubscribe of this.unsubscribers) {
      try {
        unsubscribe()
      } catch {
        // A faulty unsubscribe must never block teardown.
      }
    }
    this.unsubscribers = []
    if (this.sse && typeof this.sse.close === 'function') {
      try {
        this.sse.close()
      } catch {
        // close() must stay idempotent.
      }
    }
    if (this.ownsSse) this.sse = null
    this.ownsSse = false
    this.connected = false
  }

  /**
   * Idempotent cleanup: detaches all listeners, closes the EventSource and
   * resets the connection state. The instance may be reconnected afterwards.
   */
  disconnect() {
    this.closeConnection()
    this.activeCaseId = null
    this.events = []
    this.caseStatus = 'IDLE'
    this.streamingText = ''
    this.emitChange()
    return this
  }

  /** Full teardown: `disconnect()` + refuse any further connection. */
  close() {
    if (this.disposed) return this
    this.disconnect()
    this.disposed = true
    this.onChange = () => {}
    return this
  }

  /** Serialize observable state for rendering and assertions. */
  getState() {
    return {
      activeCaseId: this.activeCaseId,
      events: [...this.events],
      caseStatus: this.caseStatus,
      streamingText: this.streamingText,
      connected: this.connected,
      disposed: this.disposed,
      sseListeners: this.unsubscribers.length,
    }
  }
}

/**
 * Mount the activity feed into a container element.
 *
 * @param {any} container element-like target
 * @param {{
 *   caseId: string,
 *   basePath?: string,
 *   title?: string,
 *   SseClient?: Function,
 *   EventSource?: Function,
 *   maxEvents?: number,
 *   onChange?: Function,
 * }} options
 * @returns {{ stream: ForgeActivityStream, unmount: Function, render: Function, getState: Function,
 *   getEvents: Function, getStatus: Function, disconnect: Function }}
 */
export function mountForgeActivity(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('mountForgeActivity requires a container')
  const caseId = options.caseId
  if (!caseId || typeof caseId !== 'string') throw new TypeError('mountForgeActivity requires a caseId')

  let disposed = false

  const render = () => {
    if (disposed) return
    const state = stream.getState()
    container.innerHTML = renderForgeActivity({
      ...state,
      title: options.title ?? "Activité de l'agent",
      caseId: state.activeCaseId,
    })
  }

  const stream = new ForgeActivityStream({
    SseClient: options.SseClient,
    EventSource: options.EventSource,
    basePath: options.basePath ?? '',
    maxEvents: options.maxEvents,
    onChange: (state) => {
      if (disposed) return
      render()
      options.onChange?.(state)
    },
  })

  const onClick = (event) => {
    const action = event?.target?.closest?.('[data-activity-action]')?.dataset?.activityAction
    if (action === 'disconnect') stream.disconnect()
  }
  container.addEventListener?.('click', onClick)

  stream.connect(caseId, { basePath: options.basePath })
  render()

  const unmount = () => {
    if (disposed) return
    disposed = true
    container.removeEventListener?.('click', onClick)
    stream.close()
    container.innerHTML = ''
  }

  return {
    stream,
    unmount,
    render,
    getState: () => stream.getState(),
    getEvents: () => stream.getState().events,
    getStatus: () => stream.getState().caseStatus,
    disconnect: () => stream.disconnect(),
  }
}

export default { ForgeActivityStream, mountForgeActivity, renderForgeActivity, buildFeedItems }
