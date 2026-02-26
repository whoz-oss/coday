import { BehaviorSubject, Observable } from 'rxjs'
import { Namespace } from '@whoz-oss/agentos-api-client'

/**
 * NamespaceState — pure RxJS state for the namespace list.
 *
 * No Angular imports (@Injectable etc.) — instantiated by NamespaceStateService (agentos-ui).
 *
 * @param source$ - Observable of namespace list, provided by the Angular binding layer
 */
export class NamespaceState {
  private readonly _namespaces$ = new BehaviorSubject<Namespace[]>([])
  private readonly _selected$ = new BehaviorSubject<Namespace | null>(null)

  /** All available namespaces. */
  readonly namespaces$: Observable<Namespace[]> = this._namespaces$.asObservable()

  /** Currently selected namespace, null if none. */
  readonly selectedNamespace$: Observable<Namespace | null> = this._selected$.asObservable()

  constructor(source$: Observable<Namespace[]>) {
    source$.subscribe((namespaces) => {
      this._namespaces$.next(namespaces)

      // Auto-select first namespace if none selected yet
      if (this._selected$.getValue() === null && namespaces.length > 0) {
        this._selected$.next(namespaces[0] ?? null)
      }
    })
  }

  selectNamespace(id: string): void {
    const found = this._namespaces$.getValue().find((ns) => ns.id === id) ?? null
    this._selected$.next(found)
  }
}
