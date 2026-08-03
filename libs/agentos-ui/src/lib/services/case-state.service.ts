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
 * - Run the per-case mutations the drawer offers (star, rename, delete)
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

  /**
   * Rename a case. The title is applied optimistically to the list (so the drawer reflects it at
   * once) and reverted locally if the request fails, exactly like [setStarred].
   *
   * Sends the whole resource: PUT /api/cases/{id} only honours `title` (namespaceId and status
   * are mass-assignment guarded server-side), but the endpoint is @Valid and CaseDto.namespaceId
   * is @NotNull, so a title-only body would be rejected with a 400.
   *
   * The response is deliberately ignored. Single-case endpoints build their DTO with a mapper
   * that sets neither `favorite` nor `role`, so merging it back would silently un-star the case
   * and drop the ADMIN-gated actions until the next full reload. No reload either: a title-only
   * update does not bump `modified`, so the drawer's ordering is unaffected.
   *
   * Note the rename is not broadcast: the server emits no CaseUpdatedEvent on this path, so
   * other clients only see the new title on their next list load.
   */
  renameCase(caseId: string, title: string): Observable<Case> {
    // defer for the same reason as setStarred: the optimistic patch is tied to subscription.
    return defer(() => {
      const existing = this.cases().find((c) => c.id === caseId)
      if (!existing) {
        return throwError(() => new Error(`[CaseState] Case ${caseId} is not in the current list`))
      }
      const previousTitle = existing.title
      this.patchTitle(caseId, title)
      return this.caseController.updateCase(caseId, { ...existing, title }).pipe(
        catchError((err) => {
          this.patchTitle(caseId, previousTitle)
          return throwError(() => err)
        })
      )
    })
  }

  /** Set the favorite flag of a single case in-place (immutably, to re-emit the signal). */
  private patchFavorite(caseId: string, favorite: boolean): void {
    this.cases.update((list) => list.map((c) => (c.id === caseId ? { ...c, favorite } : c)))
  }

  /**
   * Set the title of a single case in-place (immutably, to re-emit the signal).
   * Accepts undefined so a revert can restore a case that had no title: '' is not nullish, and
   * the drawer falls back to the case ID only on a nullish title.
   */
  private patchTitle(caseId: string, title: string | undefined): void {
    this.cases.update((list) => list.map((c) => (c.id === caseId ? { ...c, title } : c)))
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
    this.patchTitle(caseId, title)
  }

  /**
   * Patch the status of a single case in-place.
   * Called when a CaseStatusEvent arrives via SSE so the drawer stays in sync
   * without waiting for a full reload.
   * No-op if the case is not in the current list.
   */
  updateCaseStatus(caseId: string, status: string): void {
    this.cases.update((list) =>
      list.map((c) =>
        c.id === caseId ? { ...c, status: status as import('@whoz-oss/agentos-api-client').CaseStatusEnum } : c
      )
    )
  }
}
