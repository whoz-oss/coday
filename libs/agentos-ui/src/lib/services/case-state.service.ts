import { inject, Injectable, signal } from '@angular/core'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import {
  catchError,
  concatMap,
  defer,
  EMPTY,
  endWith,
  finalize,
  ignoreElements,
  Observable,
  of,
  shareReplay,
  Subscription,
  tap,
  throwError,
} from 'rxjs'

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

  /** Last queued save per case. Different cases can still save independently. */
  private readonly pendingUpdates = new Map<string, { request: Observable<Case>; state: { confirmed: Case } }>()

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

  /** Rename through the same queue as header edits to preserve save ordering. */
  renameCase(caseId: string, title: string): Observable<Case> {
    return this.updateCaseFields(caseId, { title })
  }

  /**
   * Save editable fields in order for each case. Wait for the previous save to settle
   * before reading the current state or applying the optimistic patch, so a failed
   * save can never become the rollback snapshot of the next one.
   *
   * Once subscribed, a save finishes even if its caller navigates away. Sharing the
   * request also lets the next queued save await it without issuing it twice.
   */
  updateCaseFields(caseId: string, patch: { title?: string; runCostThreshold?: number }): Observable<Case> {
    return defer(() => {
      const pending = this.pendingUpdates.get(caseId)
      const existing = this.cases().find((c) => c.id === caseId)
      if (!existing && !pending) {
        return throwError(() => new Error(`[CaseState] Case ${caseId} is not in the current list`))
      }
      // Keep confirmed state outside the visible list, which navigation may replace
      // while an already subscribed save is waiting in the queue.
      const state = pending?.state ?? { confirmed: existing! }
      const ready = pending
        ? pending.request.pipe(
            catchError(() => EMPTY),
            ignoreElements(),
            endWith(undefined)
          )
        : of(undefined)
      const request = ready.pipe(
        concatMap(() => this.saveCaseFields(caseId, patch, state)),
        finalize(() => {
          if (this.pendingUpdates.get(caseId)?.request === request) this.pendingUpdates.delete(caseId)
        }),
        shareReplay({ bufferSize: 1, refCount: false })
      )
      this.pendingUpdates.set(caseId, { request, state })
      return request
    })
  }

  private saveCaseFields(
    caseId: string,
    patch: { title?: string; runCostThreshold?: number },
    state: { confirmed: Case }
  ): Observable<Case> {
    return defer(() => {
      const existing = state.confirmed
      const previous = { title: existing.title, runCostThreshold: existing.runCostThreshold }
      this.patchFields(caseId, patch)
      // Keep required fields such as namespaceId. Undefined values are omitted from
      // JSON; the server treats an omitted threshold as "keep existing".
      const payload: Case = { ...existing, ...patch }
      return this.caseController.updateCase(caseId, payload).pipe(
        tap((updated) => {
          state.confirmed = { ...existing, title: updated.title, runCostThreshold: updated.runCostThreshold }
          this.patchFields(caseId, { title: updated.title, runCostThreshold: updated.runCostThreshold })
        }),
        catchError((err) => {
          this.patchFields(caseId, previous)
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
   * Apply a partial field patch to a single case in-place.
   * Uses a mapped type to allow `undefined` for optional fields without carrying `null`.
   */
  private patchFields(caseId: string, patch: { title?: string; runCostThreshold?: number }): void {
    this.cases.update((list) => list.map((c) => (c.id === caseId ? { ...c, ...patch } : c)))
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
