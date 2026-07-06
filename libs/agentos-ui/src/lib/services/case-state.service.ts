import { inject, Injectable, signal } from '@angular/core'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { catchError, defer, Observable, Subscription, tap, throwError } from 'rxjs'

/**
 * CaseStateService — reactive state for the case list within a namespace.
 *
 * Shared between CaseShellComponent (which renders the drawer list) and
 * CaseChatComponent (which receives CaseUpdatedEvent via SSE and patches titles).
 *
 * Responsibilities:
 * - Load and hold the case list for the current namespace
 * - Apply in-place title patches from CaseUpdatedEvent without a full reload
 */
@Injectable({ providedIn: 'root' })
export class CaseStateService {
  private readonly caseController = inject(CaseControllerService)

  /** Reactive case list. Empty until loadCases() completes. */
  readonly cases = signal<Case[]>([])

  /** Tracks the in-flight load subscription so a newer call can cancel a stale one. */
  private loadSubscription: Subscription | null = null

  /** Namespace of the currently held cases — used to detect a namespace switch. */
  private currentNamespaceId: string | null = null

  /**
   * Load (or reload) the cases the current user is directly related to in a namespace.
   *
   * Uses the `/mine` listing (direct ADMIN/MEMBER edge only) so every listed case is
   * starrable and carries a `role`. On a same-namespace reload the previous list stays
   * visible until the new data arrives (no flicker); on a namespace switch the list is
   * cleared first, so a failed load can't leave the previous namespace's cases showing.
   */
  loadCases(namespaceId: string): void {
    if (namespaceId !== this.currentNamespaceId) {
      this.currentNamespaceId = namespaceId
      this.cases.set([])
    }
    this.loadSubscription?.unsubscribe()
    this.loadSubscription = this.caseController.listMineByParentCase(namespaceId).subscribe({
      next: (cases) => {
        this.cases.set(cases)
        this.loadSubscription = null
      },
      error: (err) => {
        console.error(`[CaseState] Failed to load cases for namespace ${namespaceId}:`, err)
        this.loadSubscription = null
      },
    })
  }

  /**
   * Soft-delete a case, then reload the current namespace's list so it drops out of the
   * drawer. Returns the request so the caller can react (e.g. leave the deleted case's view).
   */
  deleteCase(caseId: string): Observable<void> {
    return this.caseController.deleteCase(caseId).pipe(tap(() => this.reloadCurrent()))
  }

  /**
   * Star / unstar a case for the current user. The favorite flag is flipped optimistically
   * in the list (so the drawer reflects it at once) and reverted locally if the request
   * fails. Returns the request so the caller can surface an error.
   */
  setStarred(caseId: string, starred: boolean): Observable<void> {
    // defer so the optimistic flip is tied to subscription: an unsubscribed call never
    // diverges the signal from the server, and the revert always pairs with the request.
    return defer(() => {
      this.patchFavorite(caseId, starred)
      const request = starred ? this.caseController.starCase(caseId) : this.caseController.unstarCase(caseId)
      return request.pipe(
        catchError((err) => {
          this.patchFavorite(caseId, !starred)
          return throwError(() => err)
        })
      )
    })
  }

  /** Set the favorite flag of a single case in-place (immutably, to re-emit the signal). */
  private patchFavorite(caseId: string, favorite: boolean): void {
    this.cases.update((list) => list.map((c) => (c.id === caseId ? { ...c, favorite } : c)))
  }

  /** Reload the currently held namespace (no-op before the first load). */
  private reloadCurrent(): void {
    if (this.currentNamespaceId) {
      this.loadCases(this.currentNamespaceId)
    }
  }

  /**
   * Prepend a newly created case to the list.
   * Called immediately after POST /api/cases so the drawer updates without a reload.
   */
  addCase(newCase: Case): void {
    this.cases.update((list) => [newCase, ...list])
  }

  /**
   * Patch the title of a single case in-place.
   * Called when a CaseUpdatedEvent arrives via SSE.
   * No-op if the case is not in the current list.
   */
  updateCaseTitle(caseId: string, title: string): void {
    this.cases.update((list) => list.map((c) => (c.id === caseId ? { ...c, title } : c)))
  }
}
