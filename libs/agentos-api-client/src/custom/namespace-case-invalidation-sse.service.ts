import { inject, Injectable, NgZone } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'

export type NamespaceCaseInvalidationSignal = 'connected' | 'invalidated'

/**
 * Payload-free SSE notifications that invalidate a namespace's case list.
 *
 * EventSource fires `open` both for the initial connection and native reconnects.
 * Consumers should reload on every emitted signal to recover invalidations that may
 * have happened while the connection was unavailable.
 */
@Injectable({ providedIn: 'root' })
export class NamespaceCaseInvalidationSseService {
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)

  connect(namespaceId: string): Observable<NamespaceCaseInvalidationSignal> {
    const url = `${this.config.basePath}/api/namespaces/${namespaceId}/case-invalidation`

    return new Observable<NamespaceCaseInvalidationSignal>((subscriber) => {
      const source = this.zone.runOutsideAngular(() => new EventSource(url))

      source.onopen = () => this.zone.run(() => subscriber.next('connected'))
      source.addEventListener('namespace-case-invalidation', () => this.zone.run(() => subscriber.next('invalidated')))
      source.onerror = () => {
        // EventSource reconnects automatically. The following `open` triggers a reload.
      }

      return () => source.close()
    })
  }
}
