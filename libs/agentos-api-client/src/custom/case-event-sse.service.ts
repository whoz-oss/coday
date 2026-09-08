import { inject, Injectable, NgZone } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { CaseEvent } from '../lib/model/case-event'

/** Transport-level SSE event name for every CaseEvent subtype. */
export const CASE_EVENT_SSE_NAME = 'case-event'

/**
 * Parses the generic SSE payload without maintaining a transport whitelist of domain event types.
 * Domain consumers dispatch on `event.type` after this minimal envelope validation.
 */
export function parseCaseEventSsePayload(raw: string): CaseEvent | null {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || typeof (parsed as { type?: unknown }).type !== 'string') return null
    return parsed as CaseEvent
  } catch {
    return null
  }
}

@Injectable({ providedIn: 'root' })
export class CaseEventSseService {
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)

  connect(caseId: string): Observable<CaseEvent> {
    const url = `${this.config.basePath}/api/cases/${caseId}/events`

    return new Observable<CaseEvent>((subscriber) => {
      const source = this.zone.runOutsideAngular(() => new EventSource(url))
      const receive = (event: MessageEvent<string>) => {
        const parsed = parseCaseEventSsePayload(event.data)
        if (parsed) this.zone.run(() => subscriber.next(parsed))
      }

      source.addEventListener(CASE_EVENT_SSE_NAME, receive)
      source.onerror = () => {
        // EventSource reconnects itself after transient errors. Only a permanently closed
        // source completes this consumer; teardown always closes the source explicitly.
        if (source.readyState === EventSource.CLOSED) this.zone.run(() => subscriber.complete())
      }
      return () => source.close()
    })
  }
}
