import { Injectable, NgZone, inject } from '@angular/core'
import { Observable } from 'rxjs'
import { CaseEvent, Configuration } from '@whoz-oss/agentos-api-client'

/**
 * Fan-in SSE client for GET /api/cases/events/mine — the global, cross-case event
 * stream backing the companion notification window.
 *
 * Mirrors the named-event listener pattern used by the per-case stream in
 * CaseChatComponent.connectSse (the backend sends named SSE frames, e.g.
 * "event: PendingConfirmationEvent", so `onmessage` alone never fires) rather than the
 * generic onmessage-only pattern used by CaseEventSseService.
 */
@Injectable({
  providedIn: 'root',
})
export class GlobalCaseEventSseService {
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)

  /** Event types the backend actually emits on this stream (see NOTIFIABLE_EVENT_TYPES server-side). */
  private static readonly EVENT_NAMES = [
    'PendingConfirmationEvent',
    'ConfirmationResolvedEvent',
    'QuestionEvent',
    'AnswerEvent',
    'AgentFinishedEvent',
    'ErrorEvent',
  ] as const

  /**
   * Opens the SSE connection and emits each notifiable CaseEvent as it arrives.
   * Unsubscribing closes the underlying EventSource. The native EventSource
   * auto-reconnects on transport drop, so no manual reconnect logic is needed here.
   */
  connect(): Observable<CaseEvent> {
    return new Observable<CaseEvent>((subscriber) => {
      const url = `${this.config.basePath}/api/cases/events/mine`
      const eventSource = this.zone.runOutsideAngular(() => new EventSource(url))

      const handler = (msg: globalThis.MessageEvent<string>) => {
        try {
          const event = JSON.parse(msg.data) as CaseEvent
          this.zone.run(() => subscriber.next(event))
        } catch (err) {
          console.warn('[Companion SSE] failed to parse event data', err)
        }
      }

      for (const name of GlobalCaseEventSseService.EVENT_NAMES) {
        eventSource.addEventListener(name, handler)
      }

      eventSource.onerror = () => {
        // Not fatal: EventSource retries on its own. Only surface a hard failure
        // once the browser gives up and closes the connection for good.
        if (eventSource.readyState === EventSource.CLOSED) {
          this.zone.run(() => subscriber.error(new Error('Companion event stream closed')))
        }
      }

      return () => eventSource.close()
    })
  }
}
