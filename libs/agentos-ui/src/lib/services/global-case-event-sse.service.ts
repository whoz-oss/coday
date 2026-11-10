import { Injectable, NgZone, inject } from '@angular/core'
import { Observable } from 'rxjs'
import { CaseEvent, Configuration, NAMESPACE_CASE_LIST_UPDATED_EVENT } from '@whoz-oss/agentos-api-client'

/**
 * What the aggregated stream carries. Each message says what it is about, which is what lets
 * one connection replace the per-scope streams it used to take (#1616).
 */
export type GlobalStreamMessage =
  | { kind: 'case-event'; event: CaseEvent }
  | { kind: 'case-list-invalidated'; namespaceId: string }
  /**
   * The stream opened, which EventSource reports on the first connection and on every one of
   * its own reconnections. Nothing is replayed on this stream, so whatever was published while
   * it was down is gone: a consumer reloads its state on this rather than wait for the next
   * event it may never get.
   */
  | { kind: 'connected' }

/**
 * Fan-in SSE client for GET /api/cases/events/mine — the global, cross-case event stream.
 *
 * Mirrors the named-event listener pattern used by the per-case stream in
 * CaseChatComponent.connectSse (the backend sends named SSE frames, e.g.
 * "event: PendingConfirmationEvent", so `onmessage` alone never fires) rather than the
 * generic onmessage-only pattern used by CaseEventSseService.
 *
 * Cold on purpose: every subscription opens its own EventSource, so a reconnection after a
 * dropped stream is a plain resubscribe. [GlobalStreamService] owns that lifecycle.
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
   * Opens the SSE connection and emits each message as it arrives. Unsubscribing closes the
   * underlying EventSource. The native EventSource auto-reconnects on transport drop; a hard
   * failure (anything but a 200 event-stream) ends the observable, for the caller to retry.
   */
  connect(): Observable<GlobalStreamMessage> {
    return new Observable<GlobalStreamMessage>((subscriber) => {
      const url = `${this.config.basePath}/api/cases/events/mine`
      const eventSource = this.zone.runOutsideAngular(() => new EventSource(url))

      const onCaseEvent = (msg: globalThis.MessageEvent<string>) => {
        try {
          const event = JSON.parse(msg.data) as CaseEvent
          this.zone.run(() => subscriber.next({ kind: 'case-event', event }))
        } catch (err) {
          console.warn('[GlobalStream] failed to parse event data', err)
        }
      }

      const onCaseListUpdated = (msg: globalThis.MessageEvent<string>) => {
        try {
          const { namespaceId } = JSON.parse(msg.data) as { namespaceId: string }
          if (namespaceId) this.zone.run(() => subscriber.next({ kind: 'case-list-invalidated', namespaceId }))
        } catch (err) {
          console.warn('[GlobalStream] failed to parse case-list update', err)
        }
      }

      for (const name of GlobalCaseEventSseService.EVENT_NAMES) {
        eventSource.addEventListener(name, onCaseEvent)
      }
      // The per-namespace stream's frame, the namespace now in its payload (GlobalCaseEventSseController).
      eventSource.addEventListener(NAMESPACE_CASE_LIST_UPDATED_EVENT, onCaseListUpdated)

      eventSource.onopen = () => this.zone.run(() => subscriber.next({ kind: 'connected' }))

      eventSource.onerror = () => {
        // Not fatal: EventSource retries on its own. Only surface a hard failure
        // once the browser gives up and closes the connection for good.
        if (eventSource.readyState === EventSource.CLOSED) {
          this.zone.run(() => subscriber.error(new Error('Global event stream closed')))
        }
      }

      return () => eventSource.close()
    })
  }
}
