import { inject, Injectable, NgZone } from '@angular/core'
import { Observable } from 'rxjs'
import { ApiConfiguration } from '../api-configuration'
import { CaseEvent } from '../models/case-event'

/**
 * CaseEventSseService — wraps the SSE endpoint for case event streaming.
 *
 * The SSE endpoint (GET /api/cases/{caseId}/events) is intentionally excluded
 * from OpenAPI generation (tag "sse" in ng-openapi-gen.json) because EventSource
 * cannot be expressed as a standard HTTP operation.
 *
 * This service is maintained manually in src/custom/ and exported via public-api.ts.
 * It must NOT be placed in src/ root, which is owned by ng-openapi-gen.
 *
 * Protocol (from CaseEventSseController.kt):
 * - SSE event id   → CaseEvent UUID
 * - SSE event name → CaseEvent type discriminant (e.g. "MessageEvent", "ThinkingEvent")
 * - SSE event data → JSON-serialized CaseEvent subtype (polymorphic via @JsonTypeInfo)
 *
 * Usage:
 *   const events$ = this.caseEventSse.connect(caseId)
 *   events$.subscribe(event => { ... })
 *
 * The Observable completes when the EventSource closes (normal end or error).
 * Subscribers can narrow the type via the `type` discriminant field:
 *   if (event.type === 'MESSAGE') { const msg = event as MessageEvent }
 */
@Injectable({ providedIn: 'root' })
export class CaseEventSseService {
  private readonly config = inject(ApiConfiguration)
  private readonly zone = inject(NgZone)

  /**
   * Open an SSE connection for the given case and return an Observable of CaseEvents.
   *
   * The Observable completes when the server closes the stream.
   * It errors if the EventSource fails to connect or emits an error event.
   *
   * Runs EventSource callbacks outside NgZone for performance,
   * then re-enters the zone to emit — ensuring Angular change detection fires.
   */
  connect(caseId: string): Observable<CaseEvent> {
    const url = `${this.config.rootUrl}/api/cases/${caseId}/events`

    return new Observable<CaseEvent>((subscriber) => {
      const source = this.zone.runOutsideAngular(() => new EventSource(url))

      source.onmessage = (event: MessageEvent) => {
        try {
          const parsed = JSON.parse(event.data) as CaseEvent
          this.zone.run(() => subscriber.next(parsed))
        } catch (err) {
          this.zone.run(() => subscriber.error(new Error(`Failed to parse SSE event: ${err}`)))
        }
      }

      source.onerror = (_event: Event) => {
        // EventSource readyState 2 = CLOSED: the stream ended (normal or error)
        if (source.readyState === EventSource.CLOSED) {
          this.zone.run(() => subscriber.complete())
        } else {
          this.zone.run(() => subscriber.error(new Error(`SSE connection error for case ${caseId}`)))
        }
        source.close()
      }

      // Teardown: close the EventSource when the Observable is unsubscribed
      return () => source.close()
    })
  }
}
