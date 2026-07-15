import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { NamespaceControllerService, NamespaceListItem } from '@whoz-oss/agentos-api-client'
import { debounceTime, map, skip } from 'rxjs'
import { CaseChatComponent } from '../case-chat/case-chat.component'
import { CaseHomeComponent } from '../case-home/case-home.component'
import { THEME_PORT, ThemeMode } from '../../services/theme.service'
import { UserStateService } from '../../services/user-state.service'
import { CaseStateService } from '../../services/case-state.service'
import { ShellSidebarComponent } from './shell-sidebar/shell-sidebar.component'
import { ShellTopbarMobileComponent } from './shell-topbar-mobile/shell-topbar-mobile.component'
import { ShellCaseSwitcherMobileComponent } from './shell-case-switcher-mobile/shell-case-switcher-mobile.component'

/**
 * CaseShellComponent — smart layout orchestrator for the case section.
 *
 * Responsibilities (after refactor):
 * - Owns data: case list, namespace list, query params
 * - Owns routing: namespace switching, case selection, navigation
 * - Owns UI state: sidebar width, menus open/closed, theme, technical logs
 * - Delegates rendering to ShellSidebarComponent, ShellTopbarMobileComponent,
 *   ShellCaseSwitcherMobileComponent
 *
 * Active namespace and case are read from query params:
 *   ?ns=<namespaceId>   → active namespace
 *   ?case=<caseId>      → active case (renders CaseChatComponent when present)
 *
 * When no ?ns is present, auto-selects the first available namespace.
 */
@Component({
  selector: 'agentos-case-shell',
  imports: [
    CaseChatComponent,
    CaseHomeComponent,
    ShellSidebarComponent,
    ShellTopbarMobileComponent,
    ShellCaseSwitcherMobileComponent,
  ],
  templateUrl: './case-shell.component.html',
  styleUrl: './case-shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseShellComponent {
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly themePort = inject(THEME_PORT)
  private readonly userState = inject(UserStateService)
  private readonly caseState = inject(CaseStateService)
  private readonly destroyRef = inject(DestroyRef)

  // ---------------------------------------------------------------------------
  // User
  // ---------------------------------------------------------------------------

  protected readonly isAdmin = computed(() => this.userState.currentUser()?.isAdmin === true)

  protected readonly userInitials = computed(() => {
    const user = this.userState.currentUser()
    if (!user) return ''
    const first = user.firstname?.[0] ?? ''
    const last = user.lastname?.[0] ?? ''
    return (first + last).toUpperCase() || user.email?.[0]?.toUpperCase() || ''
  })

  // ---------------------------------------------------------------------------
  // Theme
  // ---------------------------------------------------------------------------

  protected readonly isDark = computed(() => {
    const t = this.themePort.theme()
    if (t === 'dark') return true
    if (t === 'light') return false
    return typeof document !== 'undefined' && document.documentElement.hasAttribute('data-theme')
  })

  // ---------------------------------------------------------------------------
  // UI state
  // ---------------------------------------------------------------------------

  /** Sidebar width in pixels — adjustable via drag handle, persisted in localStorage */
  protected readonly sidebarWidth = signal(Number(localStorage.getItem('agentos.sidebar.width')) || 300)

  /** Whether the user context menu is open */
  protected readonly menuOpen = signal(false)

  /** Whether the namespace dropdown is open */
  protected readonly nsMenuOpen = signal(false)

  /** Whether the desktop sidebar is expanded — persisted in localStorage */
  protected readonly sidebarOpen = signal(localStorage.getItem('agentos.sidebar.open') !== 'false')

  /** Whether the mobile case switcher is open */
  protected readonly mobileSwitcherOpen = signal(false)

  /** Shared technical logs toggle — passed down to CaseChatComponent */
  protected readonly showTechnical = signal(false)

  /** Whether to show the namespace picker (admin or multiple namespaces) */
  protected readonly showNsPicker = computed(() => this.isAdmin() || this.namespaces().length > 1)

  // ---------------------------------------------------------------------------
  // Query param streams
  // ---------------------------------------------------------------------------

  private readonly namespaceId$ = this.route.queryParams.pipe(map((p) => (p['ns'] as string) ?? null))

  private readonly _namespaceIdRaw = toSignal(this.namespaceId$)
  protected readonly namespaceId = computed(() => this._namespaceIdRaw() ?? null)

  private readonly _activeCaseIdRaw = toSignal(this.route.queryParams.pipe(map((p) => (p['case'] as string) ?? null)))
  protected readonly activeCaseId = computed(() => this._activeCaseIdRaw() ?? null)

  // ---------------------------------------------------------------------------
  // Data
  // ---------------------------------------------------------------------------

  /** Cases from the state service (loaded via /mine — direct ADMIN/MEMBER edge only). */
  protected readonly cases = this.caseState.cases

  protected readonly namespaces = toSignal(this.namespaceController.listAllNamespace(), {
    initialValue: [] as NamespaceListItem[],
  })

  protected readonly selectedNamespace = signal<NamespaceListItem | null>(null)

  constructor() {
    // Load the current user eagerly so isAdmin() and userInitials() are available
    // as soon as the shell renders, without waiting for a /me navigation.
    if (!this.userState.currentUser()) {
      this.userState.loadMe().pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
    }

    // Persist sidebar width to localStorage, debounced to avoid writing on every drag pixel.
    toObservable(this.sidebarWidth)
      .pipe(skip(1), debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe((width) => localStorage.setItem('agentos.sidebar.width', String(width)))

    // Sync selectedNamespace whenever namespaces load or the ?ns param changes.
    // When no ?ns is present, auto-select the first namespace and update the URL.
    effect(() => {
      const nsList = this.namespaces()
      const nsId = this.namespaceId()
      if (!nsList.length) return

      if (nsId) {
        const found = nsList.find((ns) => ns.id === nsId)
        this.selectedNamespace.set(found ?? nsList[0] ?? null)
      } else {
        const first = nsList[0]
        if (first?.id) {
          this.router.navigate(['/agentos/home'], { queryParams: { ns: first.id }, replaceUrl: true })
        }
      }
    })

    // Reload the case list whenever the namespace changes.
    effect(() => {
      const nsId = this.namespaceId()
      if (nsId) {
        this.caseState.loadCases(nsId)
      }
    })
  }

  // ---------------------------------------------------------------------------
  // Namespace
  // ---------------------------------------------------------------------------

  protected onNamespaceSelected(ns: NamespaceListItem): void {
    this.selectedNamespace.set(ns)
    this.nsMenuOpen.set(false)
    this.router.navigate(['/agentos/home'], { queryParams: { ns: ns.id } })
  }

  protected toggleNsMenu(event?: Event): void {
    event?.stopPropagation()
    event?.preventDefault()
    this.nsMenuOpen.update((v) => !v)
  }

  protected closeNsMenu(event: Event): void {
    event.stopPropagation()
    this.nsMenuOpen.set(false)
  }

  // ---------------------------------------------------------------------------
  // Cases
  // ---------------------------------------------------------------------------

  protected onCaseSelected(caseId: string): void {
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId(), case: caseId },
    })
  }

  protected onCreateRequested(): void {
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId() },
    })
  }

  protected onDeleteRequested(caseId: string): void {
    const target = this.cases().find((c) => c.id === caseId)
    const label = target?.title ?? caseId
    if (!confirm(`Delete "${label}"?`)) {
      return
    }
    this.caseState
      .deleteCase(caseId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          if (caseId === this.activeCaseId()) {
            this.router.navigate(['/agentos/home'], { queryParams: { ns: this.namespaceId() } })
          }
        },
        error: (err) => {
          console.error(`[CaseShell] Failed to delete case ${caseId}:`, err)
          alert('Could not delete the case. Please try again.')
        },
      })
  }

  protected onStarToggled(event: { id: string; starred: boolean }): void {
    this.caseState
      .setStarred(event.id, event.starred)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err) => {
          console.error(`[CaseShell] Failed to update star for case ${event.id}:`, err)
          alert('Could not update the favorite. Please try again.')
        },
      })
  }

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------

  protected navigateHome(): void {
    this.router.navigate(['/agentos/home'])
  }

  protected collapseSidebar(): void {
    this.sidebarOpen.set(false)
    localStorage.setItem('agentos.sidebar.open', 'false')
  }

  protected expandSidebar(): void {
    this.sidebarOpen.set(true)
    localStorage.setItem('agentos.sidebar.open', 'true')
  }

  protected onMenuNavigate(path: string): void {
    this.menuOpen.set(false)
    this.router.navigate([path])
  }

  // ---------------------------------------------------------------------------
  // User menu
  // ---------------------------------------------------------------------------

  protected toggleMenu(event?: MouseEvent): void {
    event?.stopPropagation()
    event?.preventDefault()
    this.menuOpen.update((v) => !v)
  }

  protected closeMenu(event: Event): void {
    event.stopPropagation()
    this.menuOpen.set(false)
  }

  protected onMenuToggleTheme(): void {
    this.menuOpen.set(false)
    const next: ThemeMode = this.isDark() ? 'light' : 'dark'
    this.themePort.setTheme(next)
  }

  protected onMenuToggleLogs(): void {
    this.menuOpen.set(false)
    this.showTechnical.update((v) => !v)
  }

  // ---------------------------------------------------------------------------
  // Mobile case switcher
  // ---------------------------------------------------------------------------

  protected toggleMobileSwitcher(): void {
    this.mobileSwitcherOpen.update((v) => !v)
  }

  protected onMobileCaseSelect(caseId: string): void {
    this.mobileSwitcherOpen.set(false)
    this.onCaseSelected(caseId)
  }

  protected onMobileCreateCase(): void {
    this.mobileSwitcherOpen.set(false)
    this.onCreateRequested()
  }

  // ---------------------------------------------------------------------------
  // Sidebar resize
  // ---------------------------------------------------------------------------

  /**
   * Start a drag session to resize the sidebar.
   * Clamps the width between 200px and 500px.
   *
   * During drag:
   * - The sidebar transition is disabled to avoid per-pixel animation lag.
   * - The sidebar width is set directly on the element style (no Angular
   *   change detection on every mousemove pixel).
   * On mouseup the final width is committed to the signal (single CD cycle).
   */
  protected onResizeStart(event: MouseEvent): void {
    event.preventDefault()
    const startX = event.clientX
    const startWidth = this.sidebarWidth()

    // Find the sidebar element and disable its CSS transition for the drag duration.
    const sidebarEl = document.querySelector<HTMLElement>('.shell-sidebar')
    if (sidebarEl) sidebarEl.style.transition = 'none'

    let currentWidth = startWidth

    const onMove = (e: MouseEvent): void => {
      const delta = e.clientX - startX
      currentWidth = Math.max(200, Math.min(500, startWidth + delta))
      if (sidebarEl) sidebarEl.style.width = `${currentWidth}px`
    }

    const onUp = (): void => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      if (sidebarEl) sidebarEl.style.transition = ''
      this.sidebarWidth.set(currentWidth)
    }

    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
  }
}
