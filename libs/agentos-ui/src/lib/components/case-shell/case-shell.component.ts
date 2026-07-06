import { Component, inject, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router'
import { CaseControllerService } from '@whoz-oss/agentos-api-client'
import { DrawerComponent, IconButtonComponent } from '@whoz-oss/design-system'
import { filter, map, merge, of } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { CaseDrawerComponent } from '../case-drawer/case-drawer.component'
import { CaseItemComponent } from '../case-item/case-item.component'
import { HeaderComponent } from '../header/header.component'

/**
 * CaseShellComponent — smart layout container for the case section.
 *
 * Responsibilities:
 * - Owns the ds-drawer layout: header + collapsible sidenav + routed content
 * - Delegates case list state to CaseStateService
 * - Passes cases to CaseDrawerComponent (presentational)
 * - Handles drawer toggle state
 * - Navigates on case selection / create
 *
 * Child routes render inside the <router-outlet> in the main content area:
 *   ''          → CaseHomeComponent
 *   ':caseId'   → CaseChatComponent
 */
@Component({
  selector: 'agentos-case-shell',
  imports: [RouterOutlet, DrawerComponent, CaseDrawerComponent, HeaderComponent, IconButtonComponent],
  templateUrl: './case-shell.component.html',
  styleUrl: './case-shell.component.scss',
})
export class CaseShellComponent {
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly caseState = inject(CaseStateService)
  private readonly caseController = inject(CaseControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly drawerOpen = signal(true)

  protected readonly cases = this.caseState.cases

  constructor() {
    this.caseState.loadCases(this.namespaceId)
  }

  /**
   * Active case id — derived reactively from router NavigationEnd events.
   *
   * `computed()` on `route.firstChild?.snapshot` does not work because the snapshot
   * is not a signal and won’t trigger re-evaluation on navigation.
   * Instead we listen to Router events and extract the caseId from the URL.
   */
  protected readonly activeCaseId = toSignal(
    merge(
      // Emit immediately for the current URL (handles component init and page refresh)
      of(this.router.url),
      // Then re-emit on every completed navigation
      this.router.events.pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        map((e) => e.urlAfterRedirects)
      )
    ).pipe(map((url) => this.extractCaseId(url))),
    { initialValue: null as string | null }
  )

  /**
   * Extract the caseId segment from the current URL.
   * URL pattern: /agentos/:namespaceId/cases/:caseId
   */
  private extractCaseId(url: string): string | null {
    const match = url.match(/\/cases\/([^/?#]+)/)
    return match?.[1] ?? null
  }

  protected toggleDrawer(): void {
    this.drawerOpen.update((v) => !v)
  }

  protected onCaseSelected(caseId: string): void {
    this.router.navigate([caseId], { relativeTo: this.route })
  }

  protected onCreateRequested(): void {
    // Navigate to the home view where the user can start a new case
    this.navigateToSectionHome()
  }

  protected onDeleteRequested(caseId: string): void {
    // Confirm with the same label the drawer row shows (CaseItemComponent.toListItem),
    // so the dialog identifies the exact case the user clicked.
    const target = this.cases().find((c) => c.id === caseId)
    const label = target ? CaseItemComponent.toListItem(target).name : caseId
    if (!confirm(`Delete "${label}"?`)) {
      return
    }
    // Soft-delete (the backend flips a `removed` flag); refresh the list on success.
    this.caseController.deleteCase(caseId).subscribe({
      next: () => {
        // If the active case was deleted, leave its (now removed) chat view.
        if (caseId === this.activeCaseId()) {
          this.navigateToSectionHome()
        }
        this.refreshCases()
      },
      error: (err) => {
        console.error(`[CaseShell] Failed to delete case ${caseId}:`, err)
        alert('Could not delete the case. Please try again.')
      },
    })
  }

  protected onStarToggled(event: { id: string; starred: boolean }): void {
    const request = event.starred ? this.caseController.starCase(event.id) : this.caseController.unstarCase(event.id)
    request.subscribe({
      next: () => this.refreshCases(),
      error: (err) => console.error(`[CaseShell] Failed to update star for case ${event.id}:`, err),
    })
  }

  /** Navigate to the case section home (the list / create view). */
  private navigateToSectionHome(): void {
    this.router.navigate(['.'], { relativeTo: this.route })
  }

  /** Called after a case is created to refresh the drawer list. */
  refreshCases(): void {
    this.caseState.loadCases(this.namespaceId)
  }
}
