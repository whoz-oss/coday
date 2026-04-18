import { Component, computed, inject, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { DrawerComponent, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { CaseDrawerComponent } from '../case-drawer/case-drawer.component'
import { HeaderComponent } from '../header/header.component'

/**
 * CaseShellComponent — smart layout container for the case section.
 *
 * Responsibilities:
 * - Owns the ds-drawer layout: header + collapsible sidenav + routed content
 * - Loads and refreshes the case list for the current namespace
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
  standalone: true,
  imports: [RouterOutlet, DrawerComponent, CaseDrawerComponent, HeaderComponent, IconButtonComponent],
  templateUrl: './case-shell.component.html',
  styleUrl: './case-shell.component.scss',
})
export class CaseShellComponent {
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly caseController = inject(CaseControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly drawerOpen = signal(true)

  /** Trigger to refresh the case list after mutations. */
  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  private readonly cases$ = this.refresh$.pipe(switchMap(() => this.caseController.listByParentCase(this.namespaceId)))

  protected readonly cases = toSignal(this.cases$, { initialValue: [] as Case[] })

  /** Active case id derived from the current child route. */
  protected readonly activeCaseId = computed(() => {
    // The child route snapshot holds the caseId param when on the chat view
    return (this.route.firstChild?.snapshot.params['caseId'] as string | undefined) ?? null
  })

  protected toggleDrawer(): void {
    this.drawerOpen.update((v) => !v)
  }

  protected onCaseSelected(caseId: string): void {
    this.router.navigate([caseId], { relativeTo: this.route })
  }

  protected onCreateRequested(): void {
    // Navigate to the home view where the user can start a new case
    this.router.navigate(['.'], { relativeTo: this.route })
  }

  /** Called by CaseHomeComponent (via router event) after a case is created. */
  refreshCases(): void {
    this.refresh$.next()
  }
}
