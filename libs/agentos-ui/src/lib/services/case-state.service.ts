import { inject, Injectable, signal } from '@angular/core'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'

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

  /** Load (or reload) all cases for a given namespace. */
  loadCases(namespaceId: string): void {
    this.caseController.listByParentCase(namespaceId).subscribe((cases) => {
      this.cases.set(cases)
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
