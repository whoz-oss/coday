import { inject, Injectable, signal } from '@angular/core'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { Subscription } from 'rxjs'

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

  /**
   * Load (or reload) the cases the current user is directly related to in a namespace.
   *
   * Uses the `/mine` listing (direct ADMIN/MEMBER edge only) so every listed case is
   * starrable and carries a `role`. A failed load leaves the previous list in place and
   * clears the in-flight handle, so a later reload (after delete/star) still runs.
   */
  loadCases(namespaceId: string): void {
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
