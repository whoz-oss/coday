import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  Case,
  CaseControllerService,
  NamespaceControllerService,
  NamespaceListItem,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { CaseChatComponent } from '../case-chat/case-chat.component'
import { CaseHomeComponent } from '../case-home/case-home.component'
import { THEME_PORT, ThemeMode } from '../../services/theme.service'
import { UserStateService } from '../../services/user-state.service'
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
  private readonly caseController = inject(CaseControllerService)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly themePort = inject(THEME_PORT)
  private readonly userState = inject(UserStateService)
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

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  private readonly cases$ = this.namespaceId$.pipe(
    switchMap((nsId) => {
      if (!nsId) return [[] as Case[]]
      return this.refresh$.pipe(switchMap(() => this.caseController.listByParentCase(nsId)))
    })
  )

  protected readonly cases = toSignal(this.cases$, { initialValue: [] as Case[] })

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
   */
  protected onResizeStart(event: MouseEvent): void {
    event.preventDefault()
    const startX = event.clientX
    const startWidth = this.sidebarWidth()

    const onMove = (e: MouseEvent): void => {
      const delta = e.clientX - startX
      const newWidth = Math.max(200, Math.min(500, startWidth + delta))
      this.sidebarWidth.set(newWidth)
      localStorage.setItem('agentos.sidebar.width', String(newWidth))
    }

    const onUp = (): void => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
    }

    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Called after a case is created to refresh the sidebar list. */
  refreshCases(): void {
    this.refresh$.next()
  }
}
