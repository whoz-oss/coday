import { inject, Injectable } from '@angular/core'
import { BehaviorSubject, Observable, shareReplay } from 'rxjs'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { NamespaceState } from './namespace.state'

/**
 * NamespaceStateService — Angular binding layer over NamespaceState.
 *
 * Responsibilities:
 * - Inject NamespaceControllerService (Angular HTTP)
 * - Trigger the initial load via init() (called by AgentosShellComponent on init)
 * - Expose initialized$ for the shell and the agentosReadyGuard
 * - Delegate state reads/writes to the pure NamespaceState
 */
@Injectable({ providedIn: 'root' })
export class NamespaceStateService {
  private readonly namespaceApi = inject(NamespaceControllerService)

  private readonly _initialized$ = new BehaviorSubject<boolean>(false)

  /** Emits true once the initial namespace list has been loaded. */
  readonly initialized$: Observable<boolean> = this._initialized$.asObservable()

  private state: NamespaceState | null = null

  /** All available namespaces. Delegates to NamespaceState. */
  get namespaces$(): Observable<Namespace[]> {
    return this.getState().namespaces$
  }

  /** Currently selected namespace, null if none. Delegates to NamespaceState. */
  get selectedNamespace$(): Observable<Namespace | null> {
    return this.getState().selectedNamespace$
  }

  /**
   * Load namespaces from the API and initialize the state.
   * Should be called once by AgentosShellComponent on init.
   * Subsequent calls are no-ops if already initialized.
   */
  init(): void {
    if (this._initialized$.getValue()) return

    console.log('init 2')
    // shareReplay(1) ensures the HTTP request fires only once
    // even though both NamespaceState and the initialized$ listener subscribe to it.
    const source$ = this.namespaceApi.listAll(undefined).pipe(shareReplay(1))

    this.state = new NamespaceState(source$)

    source$.subscribe({
      next: () => this._initialized$.next(true),
      error: () => {
        // Unblock the guard even on error so the app doesn't hang
        this._initialized$.next(true)
      },
    })
  }

  selectNamespace(id: string): void {
    this.getState().selectNamespace(id)
  }

  private getState(): NamespaceState {
    if (!this.state) {
      // Lazy fallback: create an empty state if init() was never called
      this.state = new NamespaceState(new BehaviorSubject<Namespace[]>([]).asObservable())
    }
    return this.state
  }
}
