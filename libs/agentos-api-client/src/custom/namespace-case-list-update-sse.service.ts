import { inject, Injectable, NgZone } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'

export type NamespaceCaseListUpdateSignal = 'connected' | 'updated'

/**
 * Metadata-free SSE notifications with a neutral `{}` data payload that indicate
 * a namespace's case list was updated.
 *
 * EventSource fires `open` both for the initial connection and native reconnects.
 * Consumers should reload on every emitted signal to recover updates that may
 * have happened while the connection was unavailable.
 */
@Injectable({ providedIn: 'root' })
export class NamespaceCaseListUpdateSseService {
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)

  connect(namespaceId: string): Observable<NamespaceCaseListUpdateSignal> {
    const url = `${this.config.basePath}/api/namespaces/${encodeURIComponent(namespaceId)}/case-list-updates`

    return new Observable<NamespaceCaseListUpdateSignal>((subscriber) => {
      const source = this.zone.runOutsideAngular(() => new EventSource(url))

      source.onopen = () => this.zone.run(() => subscriber.next('connected'))
      source.addEventListener('namespace-case-list-updated', () => this.zone.run(() => subscriber.next('updated')))
      source.onerror = () => {
        // EventSource reconnects automatically. The following `open` triggers a reload.
      }

      return () => source.close()
    })
  }
}
