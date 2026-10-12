import { Injectable, InjectionToken, NgZone, OnDestroy, inject } from '@angular/core'
import { HttpClient, HttpHeaders } from '@angular/common/http'
import { BehaviorSubject, EMPTY, Observable, Subscription, catchError, switchMap, tap } from 'rxjs'
import { RunEvent } from './models'
import { generateCorrelationId } from './factory-api.service'

/**
 * Origin used to prefix AgentOS API calls (`/api/case-events/*`, `/api/cases/*`).
 *
 * The factory-cockpit is deployed under `<base href="/factory/">`. Angular's
 * `HttpClient` uses the fetch API, which resolves relative URLs against
 * `document.baseURI` (i.e. `https://host/factory/`). A path like
 * `/api/case-events/…` is therefore resolved as
 * `https://host/factory/api/case-events/…`, which the gateway does NOT route
 * to the AgentOS backend (it only knows `/api/case-events/…` at the root).
 *
 * Prefixing every AgentOS API call with `window.location.origin` (injected via
 * this token) makes the URL absolute from the true server origin, bypassing the
 * `<base href>` resolution entirely.
 *
 * - **Production / dev-server**: `window.location.origin` (e.g.
 *   `https://host` or `http://localhost:4300`).  The gateway and dev proxy both
 *   route `/api/case-events/*` and `/api/cases/*` from the root origin.
 * - **Tests** (jsdom): the token is overridden with `''` so that
 *   `HttpTestingController` can match bare paths like
 *   `/api/case-events/by-parentId/{caseId}` without an origin prefix.
 */
export const AGENTOS_API_ORIGIN = new InjectionToken<string>('AGENTOS_API_ORIGIN', {
  providedIn: 'root',
  factory: () => (typeof window !== 'undefined' && window.location?.origin ? window.location.origin : ''),
})

/**
 * Load state of the AgentOS case event feed.
 *
 * - `idle`    — no caseId active; Factory events are used.
 * - `loading` — REST history fetch in flight.
 * - `error`   — REST fetch failed; no AgentOS events available.
 * - `ready`   — history loaded; SSE may append live events.
 */
export type CaseEventLoadState = 'idle' | 'loading' | 'error' | 'ready'

/**
 * Minimal shape of an AgentOS case event as received over REST/SSE.
 *
 * Declared locally so factory-cockpit does not depend on `agentos-api-client`
 * (which requires `provideApi()` / `Configuration` injection that is not
 * registered in this app's providers).
 *
 * Fields are optional except `type` and `id` (used for deduplication).
 */
export interface AgentOsCaseEvent {
  /** Discriminator — present on every subtype. */
  type: string
  /** Stable UUID used for deduplication between REST history and SSE stream. */
  id: string
  caseId?: string
  timestamp?: string
  /** MessageEvent */
  actor?: { displayName?: string; role?: string }
  /**
   * MessageEvent content. The current AgentOS contract serializes Text as
   * `{ type: 'Text', content: string }`; `text` is retained for older payloads.
   * Image blocks intentionally contribute no textual value.
   */
  content?: Array<{ type?: string; content?: unknown; text?: unknown }>
  /** ToolRequestEvent / ToolResponseEvent */
  toolName?: string
  toolRequestId?: string
  /** ToolResponseEvent */
  durationMs?: number
  success?: boolean
  /** ErrorEvent / WarnEvent */
  message?: string
}

/**
 * Format an ISO-8601 timestamp as HH:mm:ss in UTC.
 *
 * UTC is used deliberately: AgentOS timestamps are always UTC ISO-8601 strings,
 * and using local time would produce runner-dependent values in tests.
 * Returns `'--:--:--'` for invalid or absent values.
 */
function formatClock(iso: string | undefined): string {
  if (!iso) return '--:--:--'
  try {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return '--:--:--'
    const hh = String(d.getUTCHours()).padStart(2, '0')
    const mm = String(d.getUTCMinutes()).padStart(2, '0')
    const ss = String(d.getUTCSeconds()).padStart(2, '0')
    return `${hh}:${mm}:${ss}`
  } catch {
    return '--:--:--'
  }
}

/**
 * Map one {@link AgentOsCaseEvent} to a {@link RunEvent} for the event log.
 *
 * Returns `null` for event types that carry no useful display text
 * (TextChunkEvent, AgentFinishedEvent, AgentRunningEvent, CaseStatusEvent).
 * These are lifecycle/streaming signals that add noise without value in the log.
 *
 * Note on ThinkingEvent: the backend DTO has no text field — it is a pure
 * activity signal. We map it to a fixed label so it appears as a distinct
 * row type (useful for timeline filtering) without fabricating content.
 *
 * MessageContent.Text is serialized by the current AgentOS API as
 * `{ type: 'Text', content: string }`. Older payloads may expose `text`, so both
 * shapes are accepted. Non-text content (currently Image) is not fabricated.
 */
export function mapAgentOsEventToRunEvent(e: AgentOsCaseEvent): RunEvent | null {
  const time = formatClock(e.timestamp)

  switch (e.type) {
    case 'MessageEvent': {
      const name = e.actor?.displayName ?? e.actor?.role ?? 'agent'
      const messageText = (e.content ?? [])
        .map((item) => {
          if (typeof item.content === 'string' && item.type?.toLowerCase() !== 'image') return item.content
          return typeof item.text === 'string' ? item.text : ''
        })
        .filter((part) => part.length > 0)
        .join('\n')
      return {
        time,
        type: 'agent_message',
        text: messageText ? `[${name}] ${messageText}` : `[${name}]`,
        messageText,
        actorLabel: name,
      }
    }
    case 'ToolRequestEvent':
      return {
        time,
        type: 'tool_call',
        tool: e.toolName,
        text: e.toolName ?? 'tool request',
      }
    case 'ToolResponseEvent': {
      const durationSec = typeof e.durationMs === 'number' && isFinite(e.durationMs) ? e.durationMs / 1000 : undefined
      return {
        time,
        type: 'tool_call',
        tool: e.toolName,
        text: `${e.toolName ?? 'tool'} → ${e.success ? 'ok' : 'error'}`,
        durationSec,
      }
    }
    case 'ThinkingEvent':
      // ThinkingEvent has no text field in the backend DTO.
      // Map as a fixed label so the row appears with the 'thinking' type.
      return { time, type: 'thinking', text: '(thinking)' }
    case 'ErrorEvent':
    case 'WarnEvent':
      return { time, type: 'log', text: `[${e.type}] ${e.message ?? ''}` }
    // Lifecycle / streaming events: skip — no display value.
    case 'TextChunkEvent':
    case 'AgentFinishedEvent':
    case 'AgentRunningEvent':
    case 'AgentSelectedEvent':
    case 'CaseStatusEvent':
    case 'CaseUpdatedEvent':
      return null
    default:
      return { time, type: 'log', text: `[${e.type}]` }
  }
}

/**
 * State service for AgentOS case event loading.
 *
 * Implements the two-layer pattern: this service owns all HTTP + SSE
 * communication for case events; the EventLogComponent injects this service
 * and never touches HttpClient or EventSource directly.
 *
 * Lifecycle:
 *  1. Call `activate(caseId)` when a case is selected.
 *     → Fetches REST history, then opens SSE for live events.
 *     → Deduplicates by event `id` (REST history may overlap with SSE replay).
 *  2. Call `clear()` when the selection is removed.
 *     → Closes SSE, resets state to idle.
 *  3. `ngOnDestroy` closes any open SSE connection.
 *
 * Observables exposed:
 *  - `state$`  — current {@link CaseEventLoadState}
 *  - `events$` — accumulated {@link RunEvent}[] (history + live)
 *
 * URL construction:
 *  All AgentOS API paths are prefixed with {@link AGENTOS_API_ORIGIN} so that
 *  the absolute URL bypasses the `<base href="/factory/">` resolution that
 *  Angular's fetch-based HttpClient would otherwise apply, which would prepend
 *  `/factory/` to every `/api/*` path and make the gateway unable to route
 *  the request to the AgentOS backend.
 */
@Injectable()
export class CaseEventStateService implements OnDestroy {
  private readonly http = inject(HttpClient)
  private readonly zone = inject(NgZone)
  private readonly apiOrigin = inject(AGENTOS_API_ORIGIN)

  private readonly _state$ = new BehaviorSubject<CaseEventLoadState>('idle')
  private readonly _events$ = new BehaviorSubject<RunEvent[]>([])

  /** Emits the current load state. */
  readonly state$: Observable<CaseEventLoadState> = this._state$.asObservable()
  /** Emits the accumulated display events (REST history + SSE live). */
  readonly events$: Observable<RunEvent[]> = this._events$.asObservable()

  /**
   * Set of event ids already loaded from the REST history.
   * Used to skip SSE re-emissions of the same event (replay overlap).
   */
  private readonly seenIds = new Set<string>()

  /** Active pipeline subscription (REST fetch → SSE stream). */
  private pipeline: Subscription | null = null

  /**
   * Activate the service for a specific case.
   *
   * Cancels any existing pipeline (previous case), resets state to `loading`,
   * fetches the REST history, then opens the SSE stream for live events.
   *
   * If the REST fetch fails the state is set to `error` and no SSE is opened.
   */
  activate(caseId: string): void {
    this.cancelPipeline()
    this._state$.next('loading')
    this._events$.next([])
    this.seenIds.clear()

    this.pipeline = this.fetchHistory(caseId)
      .pipe(
        tap((rawEvents) => {
          const mapped = this.mapAndDeduplicate(rawEvents)
          this._events$.next(mapped)
          this._state$.next('ready')
        }),
        switchMap(() => this.openSse(caseId)),
        tap((liveEvent) => {
          if (this.seenIds.has(liveEvent.id)) return // already in history
          this.seenIds.add(liveEvent.id)
          const mapped = mapAgentOsEventToRunEvent(liveEvent)
          if (mapped) {
            this._events$.next([...this._events$.getValue(), mapped])
          }
        }),
        catchError(() => {
          this._state$.next('error')
          return EMPTY
        })
      )
      .subscribe()
  }

  /**
   * Deactivate: close SSE, reset to idle state.
   */
  clear(): void {
    this.cancelPipeline()
    this._state$.next('idle')
    this._events$.next([])
    this.seenIds.clear()
  }

  ngOnDestroy(): void {
    this.cancelPipeline()
    this._state$.complete()
    this._events$.complete()
  }

  // ─────────────────────────────────────────────────────────────────
  // Private helpers
  // ─────────────────────────────────────────────────────────────────

  /**
   * Build an absolute AgentOS API URL that bypasses `<base href="/factory/">`.
   *
   * Prefixing with `this.apiOrigin` (= `window.location.origin` at runtime,
   * `''` in tests) ensures the browser sends the request to the root origin
   * path, not to `/factory/api/…`.
   */
  private agentOsUrl(path: string): string {
    return `${this.apiOrigin}${path}`
  }

  private fetchHistory(caseId: string): Observable<AgentOsCaseEvent[]> {
    const headers = new HttpHeaders().set('X-Correlation-Id', generateCorrelationId())
    return this.http.get<AgentOsCaseEvent[]>(
      this.agentOsUrl(`/api/case-events/by-parentId/${encodeURIComponent(caseId)}`),
      { headers }
    )
  }

  private openSse(caseId: string): Observable<AgentOsCaseEvent> {
    const url = this.agentOsUrl(`/api/cases/${encodeURIComponent(caseId)}/events`)
    return new Observable<AgentOsCaseEvent>((subscriber) => {
      const source = this.zone.runOutsideAngular(() => new EventSource(url))

      const onEvent = (event: MessageEvent) => {
        try {
          const parsed = JSON.parse(event.data as string) as AgentOsCaseEvent
          this.zone.run(() => subscriber.next(parsed))
        } catch {
          // Malformed SSE frame: skip silently — do not kill the stream.
        }
      }

      source.addEventListener('case-event', onEvent)
      // onerror: EventSource reconnects by design — do not error the Observable.
      source.onerror = () => undefined

      return () => source.close()
    })
  }

  private mapAndDeduplicate(rawEvents: AgentOsCaseEvent[]): RunEvent[] {
    const result: RunEvent[] = []
    for (const e of rawEvents) {
      this.seenIds.add(e.id)
      const mapped = mapAgentOsEventToRunEvent(e)
      if (mapped) result.push(mapped)
    }
    return result
  }

  private cancelPipeline(): void {
    this.pipeline?.unsubscribe()
    this.pipeline = null
  }
}
