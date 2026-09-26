/**
 * Factory Cockpit — resilient Server-Sent Events client.
 *
 * Vanilla ESM, zero dependencies, zero build step. Wraps a native
 * `EventSource` with:
 *
 *   - a tiny event bus (`on` / `off` / `emit`) so views decode once and fan out;
 *   - named-event dispatch for the workflow-projection family (plus any custom
 *     event a subscription registers);
 *   - auto-reconnect that never accumulates sockets or timers;
 *   - an idempotent `close()` that guarantees zero leaks.
 */

/** Projection invalidation events emitted by `workflowProjectionSseHub`. */
export const WORKFLOW_PROJECTION_EVENTS = Object.freeze([
  'workflow-projection-updated',
  'workflow-projection-removed',
  'workflow-projection-restored',
  'workflow-projection-purged',
])

const DEFAULT_RECONNECT_DELAY_MS = 3000

/** Parse an SSE `data` payload, tolerating non-JSON frames. */
function parseEventData(raw) {
  if (raw === undefined || raw === null || raw === '') return null
  if (typeof raw !== 'string') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

export class SseClient {
  /**
   * @param {string} url
   * @param {{ EventSource?: typeof EventSource, reconnectDelayMs?: number,
   *   events?: string[], onOpen?: Function, onError?: Function }} [options]
   */
  constructor(url, options = {}) {
    this.url = url
    this.options = options
    this.EventSourceImpl = options.EventSource ?? globalThis.EventSource
    this.reconnectDelayMs = options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS
    this.onOpen = typeof options.onOpen === 'function' ? options.onOpen : null
    this.onError = typeof options.onError === 'function' ? options.onError : null

    this.eventSource = null
    this.listeners = new Map()
    this.boundEvents = new Set([...WORKFLOW_PROJECTION_EVENTS, ...(options.events ?? [])])
    this.attachedHandlers = new Map()
    this.closed = false
    this.connected = false
    this.reconnectTimer = null
  }

  /** Register a handler for an event; returns an unsubscribe function. */
  on(event, handler) {
    if (typeof handler !== 'function') return () => {}
    if (!this.listeners.has(event)) this.listeners.set(event, new Set())
    this.listeners.get(event).add(handler)
    this.boundEvents.add(event)
    if (this.eventSource) this.attachEvent(event)
    return () => this.off(event, handler)
  }

  /** Remove a previously registered handler. */
  off(event, handler) {
    const handlers = this.listeners.get(event)
    if (handlers) {
      handlers.delete(handler)
      if (handlers.size === 0) this.listeners.delete(event)
    }
    return this
  }

  /** Dispatch `data` to every handler subscribed to `event`. */
  emit(event, data) {
    const handlers = this.listeners.get(event)
    if (!handlers) return
    for (const handler of [...handlers]) {
      try {
        handler(data, event)
      } catch {
        // A faulty listener must never break the bus or the SSE stream.
      }
    }
  }

  /** Open the underlying `EventSource`, replacing any previous connection. */
  connect() {
    if (this.closed) return this
    this.clearReconnectTimer()
    if (typeof this.EventSourceImpl !== 'function') return this

    this.closeEventSource()
    this.eventSource = new this.EventSourceImpl(this.url)
    this.attachedHandlers = new Map()
    for (const event of this.boundEvents) this.attachEvent(event)
    this.attachEvent('message')
    this.attachEvent('open')
    this.attachEvent('error')
    return this
  }

  /** Attach a single named listener if not already attached. */
  attachEvent(event) {
    if (!this.eventSource || this.attachedHandlers.has(event)) return
    const handler = (evt) => this.handleEvent(event, evt)
    this.attachedHandlers.set(event, handler)
    this.eventSource.addEventListener(event, handler)
  }

  /** Route a raw EventSource event to the bus. */
  handleEvent(event, evt) {
    if (event === 'open') {
      this.connected = true
      this.emit('open', evt)
      this.onOpen?.(evt)
      return
    }
    if (event === 'error') {
      this.handleError(evt)
      return
    }
    const data = parseEventData(evt?.data)
    this.emit(event, data)
  }

  /** Close the failing socket and schedule a single reconnect. */
  handleError(evt) {
    this.connected = false
    this.emit('error', evt)
    this.onError?.(evt)
    if (this.closed) return

    this.closeEventSource()
    if (this.reconnectTimer !== null) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, this.reconnectDelayMs)
    if (typeof this.reconnectTimer?.unref === 'function') this.reconnectTimer.unref()
  }

  /** Detach every listener and close the socket, without marking as closed. */
  closeEventSource() {
    if (this.eventSource) {
      for (const [event, handler] of this.attachedHandlers) {
        try {
          this.eventSource.removeEventListener(event, handler)
        } catch {
          // EventSource mock/implementation may lack removeEventListener.
        }
      }
      try {
        this.eventSource.close()
      } catch {
        // close() must stay idempotent and never throw.
      }
    }
    this.attachedHandlers = new Map()
    this.eventSource = null
    this.connected = false
  }

  /** Clear a pending reconnect timer, if any. */
  clearReconnectTimer() {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  /**
   * Idempotent teardown: safe to call any number of times. Clears timers,
   * closes the socket, detaches handlers and empties the listener registry.
   */
  close() {
    this.closed = true
    this.clearReconnectTimer()
    this.closeEventSource()
    this.listeners.clear()
    this.boundEvents.clear()
    return this
  }
}

export default SseClient
