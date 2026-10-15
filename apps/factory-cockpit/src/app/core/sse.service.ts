import { InjectionToken, Injectable, OnDestroy, inject } from '@angular/core'
import { Observable, Subject } from 'rxjs'

/** Named SSE events published by the workflow projection hub. */
export const SSE_EVENT_NAMES = [
  'workflow-projection-updated',
  'workflow-projection-removed',
  'workflow-projection-restored',
  'workflow-projection-purged',
] as const

export type SseEventName = (typeof SSE_EVENT_NAMES)[number]

/** A single projection invalidation received over the SSE stream. */
export interface SseInvalidation {
  type: SseEventName
  data: unknown
  workflowId?: string
  namespaceId?: string
  revision?: number
  receivedAt: number
}

/** Factory used to instantiate the underlying `EventSource` (overridable in tests). */
export type EventSourceFactory = (url: string) => EventSource

export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('COCKPIT_V2_EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => {
    if (typeof EventSource === 'undefined') {
      throw new Error('EventSource is not available in this environment')
    }
    return new EventSource(url)
  },
})

const STREAM_PATH = '/api/factory/workflows/stream'
const MAX_RECONNECT_DELAY_MS = 30_000

/**
 * Resilient client for the factory workflow projection SSE stream.
 *
 * The service subscribes to the four named projection events and turns each one
 * into an {@link SseInvalidation} on `invalidations$`. When the connection drops
 * it schedules a bounded-backoff reconnect; once a dropped connection is
 * restored it emits on `reconnected$` so subscribers know to re-fetch the REST
 * state (SSE carries invalidations, not payloads).
 *
 * All socket access is funneled through {@link EVENT_SOURCE_FACTORY}, letting
 * tests inject a fake `EventSource` (jsdom does not implement one).
 */
@Injectable({ providedIn: 'root' })
export class SseService implements OnDestroy {
  private readonly eventSourceFactory = inject(EVENT_SOURCE_FACTORY)

  private source: EventSource | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0
  private hasConnected = false
  private namespaceId: string | undefined
  private disposed = false

  private readonly invalidationsSubject = new Subject<SseInvalidation>()
  private readonly reconnectedSubject = new Subject<void>()
  private readonly connectionSubject = new Subject<boolean>()

  /** Emits once per received projection event. */
  readonly invalidations$: Observable<SseInvalidation> = this.invalidationsSubject.asObservable()
  /** Emits after a dropped connection has been re-established. */
  readonly reconnected$: Observable<void> = this.reconnectedSubject.asObservable()
  /** Emits `true`/`false` as the connection opens/closes. */
  readonly connectionState$: Observable<boolean> = this.connectionSubject.asObservable()

  /** Open (or re-open) the stream, optionally filtering by namespace. */
  connect(namespaceId?: string): void {
    this.namespaceId = namespaceId
    this.open()
  }

  /** Close the stream and cancel any pending reconnect. */
  close(): void {
    this.clearReconnect()
    this.closeSource()
    this.hasConnected = false
    this.reconnectAttempts = 0
    this.connectionSubject.next(false)
  }

  ngOnDestroy(): void {
    this.disposed = true
    this.close()
    this.invalidationsSubject.complete()
    this.reconnectedSubject.complete()
    this.connectionSubject.complete()
  }

  private open(): void {
    if (this.disposed) return
    this.clearReconnect()
    this.closeSource()

    let source: EventSource
    try {
      source = this.eventSourceFactory(this.buildUrl())
    } catch {
      // EventSource unavailable (SSR/jsdom) or construction failed: retry later.
      this.scheduleReconnect()
      return
    }

    this.source = source
    source.onopen = () => this.handleOpen()
    source.onerror = () => this.handleError()
    for (const name of SSE_EVENT_NAMES) {
      source.addEventListener(name, (event) => this.handleEvent(name, event as MessageEvent))
    }
  }

  private buildUrl(): string {
    const namespace = this.namespaceId?.trim()
    return namespace ? `${STREAM_PATH}?namespaceId=${encodeURIComponent(namespace)}` : STREAM_PATH
  }

  private handleOpen(): void {
    this.reconnectAttempts = 0
    if (this.hasConnected) this.reconnectedSubject.next()
    this.hasConnected = true
    this.connectionSubject.next(true)
  }

  private handleEvent(type: SseEventName, event: MessageEvent): void {
    const data = this.parse(event.data)
    const payload = typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : undefined
    const invalidation: SseInvalidation = { type, data, receivedAt: Date.now() }
    if (typeof payload?.['workflowId'] === 'string') invalidation.workflowId = payload['workflowId']
    if (typeof payload?.['namespaceId'] === 'string') invalidation.namespaceId = payload['namespaceId']
    if (typeof payload?.['revision'] === 'number') invalidation.revision = payload['revision']
    this.invalidationsSubject.next(invalidation)
  }

  private parse(raw: unknown): unknown {
    if (typeof raw !== 'string' || raw.length === 0) return raw
    try {
      return JSON.parse(raw)
    } catch {
      return raw
    }
  }

  private handleError(): void {
    this.connectionSubject.next(false)
    this.closeSource()
    this.scheduleReconnect()
  }

  private scheduleReconnect(): void {
    if (this.disposed || this.reconnectTimer !== null) return
    const delay = Math.min(2000 * 2 ** this.reconnectAttempts, MAX_RECONNECT_DELAY_MS)
    this.reconnectAttempts += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.open()
    }, delay)
  }

  private clearReconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private closeSource(): void {
    if (!this.source) return
    this.source.onopen = null
    this.source.onerror = null
    this.source.close()
    this.source = null
  }
}
