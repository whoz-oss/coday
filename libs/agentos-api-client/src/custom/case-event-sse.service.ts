import { inject, Injectable, NgZone } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { CaseEvent } from '../lib/model/case-event'

/**
 * CaseEventSseService — wraps the SSE endpoint for case event streaming.
 *
 * The SSE endpoint (GET /api/cases/{caseId}/events) is intentionally excluded
 * from OpenAPI generation (tag "sse" in openapitools.json) because EventSource
 * cannot be expressed as a standard HTTP operation.
 *
 * This service is maintained manually in src/custom/ and exported via src/index.ts.
 *
 * Protocol (from CaseEventSseController.kt):
 * - SSE event id   → CaseEvent UUID
 * - SSE event name → stable "case-event" channel
 * - SSE event data → JSON-serialized CaseEvent subtype (polymorphic via `type`)
 *
 * BREAKING protocol: clients must listen to "case-event" and discriminate payloads
 * through `data.type`; individual CaseEvent type names are no longer SSE channels.
 *
 * Usage:
 *   const events$ = this.caseEventSse.connect(caseId)
 *   events$.subscribe(event => { ... })
 *
 * The native EventSource reconnection policy is preserved on transient transport errors.
 * Subscribers can narrow the type via the `type` discriminant field:
 *   if (event.type === 'MESSAGE') { const msg = event as MessageEvent }
 */
@Injectable({ providedIn: 'root' })
export class CaseEventSseService {
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)

  /**
   * Open an SSE connection for the given case and return an Observable of CaseEvents.
   *
   * EventSource reconnects automatically after transient transport failures; this
   * Observable remains subscribed until its consumer unsubscribes.
   *
   * Runs EventSource callbacks outside NgZone for performance,
   * then re-enters the zone to emit — ensuring Angular change detection fires.
   */
  connect(caseId: string): Observable<CaseEvent> {
    const url = `${this.config.basePath}/api/cases/${caseId}/events`

    return new Observable<CaseEvent>((subscriber) => {
      const source = this.zone.runOutsideAngular(() => new EventSource(url))

      const onCaseEvent = (event: MessageEvent) => {
        try {
          const parsed = JSON.parse(event.data) as CaseEvent
          this.zone.run(() => subscriber.next(parsed))
        } catch (err) {
          this.zone.run(() => subscriber.error(new Error(`Failed to parse SSE event: ${err}`)))
        }
      }

      source.addEventListener('case-event', onCaseEvent)

      source.onerror = () => {
        // Do not close or error the Observable here: EventSource reconnects by design.
        // A server-side saturation invalidation intentionally reaches this path so that
        // the browser reconnects and receives the durable replay.
      }

      return () => source.close()
    })
  }
}
