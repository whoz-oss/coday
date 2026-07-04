import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  Case,
  CaseControllerService,
  CaseStatusEnum,
  NamespaceControllerService,
  NamespaceListItem,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { CaseDrawerComponent } from '../case-drawer/case-drawer.component'
import { CaseChatComponent } from '../case-chat/case-chat.component'
import { CaseHomeComponent } from '../case-home/case-home.component'
import { THEME_PORT, ThemeMode } from '../../services/theme.service'
import { UserStateService } from '../../services/user-state.service'

/**
 * CaseShellComponent — smart layout container for the case section.
 *
 * Responsibilities:
 * - Owns the fixed sidebar layout: logo + namespace picker + case list
 * - Loads and refreshes the case list for the current namespace
 * - Loads all namespaces for the namespace picker
 * - Passes cases to CaseDrawerComponent (presentational)
 * - Handles namespace switching via picker
 * - Navigates on case selection / create
 *
 * Active namespace and case are read from query params:
 *   ?ns=<namespaceId>   → active namespace
 *   ?case=<caseId>      → active case (renders CaseChatComponent when present)
 *
 * When no ?ns is present, auto-selects the first available namespace.
 */
@Component({
  selector: 'agentos-case-shell',
  imports: [CaseDrawerComponent, CaseChatComponent, CaseHomeComponent],
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

  protected readonly isAdmin = computed(() => this.userState.currentUser()?.isAdmin === true)

  /** Controls visibility of the namespace dropdown menu */
  protected readonly nsMenuOpen = signal(false)

  /** Sidebar width in pixels — adjustable via drag handle */
  protected readonly sidebarWidth = signal(300)

  /** Whether dark mode is currently active */
  protected readonly isDark = computed(() => {
    const t = this.themePort.theme()
    // 'system' resolves based on the OS preference; we check the applied data-theme attribute
    if (t === 'dark') return true
    if (t === 'light') return false
    return typeof document !== 'undefined' && document.documentElement.hasAttribute('data-theme')
  })

  /** Shared technical logs toggle — passed down to CaseChatComponent via @Input */
  protected readonly showTechnical = signal(false)

  /** Search query for filtering cases in the sidebar */
  protected readonly searchQuery = signal('')

  /** Whether to show the namespace picker (admin or multiple namespaces) */
  protected readonly showNsPicker = computed(() => this.isAdmin() || this.namespaces().length > 1)

  /** Controls visibility of the user context menu */
  protected readonly menuOpen = signal(false)

  /** Controls visibility of the mobile case switcher */
  protected readonly mobileSwitcherOpen = signal(false)

  // ---------------------------------------------------------------------------
  // Query param streams
  // ---------------------------------------------------------------------------

  /** Active namespace id from ?ns query param */
  private readonly namespaceId$ = this.route.queryParams.pipe(map((params) => (params['ns'] as string) ?? null))

  // toSignal without initialValue returns T | undefined; we normalise to string | null via computed()
  private readonly _namespaceIdRaw = toSignal(this.namespaceId$)
  protected readonly namespaceId = computed(() => this._namespaceIdRaw() ?? null)

  /** Active case id from ?case query param */
  private readonly _activeCaseIdRaw = toSignal(
    this.route.queryParams.pipe(map((params) => (params['case'] as string) ?? null))
  )
  protected readonly activeCaseId = computed(() => this._activeCaseIdRaw() ?? null)

  // ---------------------------------------------------------------------------
  // Data
  // ---------------------------------------------------------------------------

  /** Trigger to refresh the case list after mutations. */
  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  private readonly cases$ = this.namespaceId$.pipe(
    switchMap((nsId) => {
      if (!nsId) return [[] as Case[]]
      return this.refresh$.pipe(switchMap(() => this.caseController.listByParentCase(nsId)))
    })
  )

  private readonly allCases = toSignal(this.cases$, { initialValue: [] as Case[] })

  /** Cases filtered by the current search query */
  protected readonly cases = computed(() => {
    const query = this.searchQuery().trim().toLowerCase()
    if (!query) return this.allCases()
    return this.allCases().filter((c) => {
      const title = (c.title ?? '').toLowerCase()
      return title.includes(query)
    })
  })

  /** All namespaces available to the current user — for the namespace picker */
  protected readonly namespaces = toSignal(this.namespaceController.listAllNamespace(), {
    initialValue: [] as NamespaceListItem[],
  })

  /** Currently active namespace object, derived from the loaded list + query param */
  protected readonly selectedNamespace = signal<NamespaceListItem | null>(null)

  /** Initials of the current user for the menu trigger button */
  protected readonly userInitials = computed(() => {
    const user = this.userState.currentUser()
    if (!user) return ''
    const first = user.firstname?.[0] ?? ''
    const last = user.lastname?.[0] ?? ''
    return (first + last).toUpperCase() || user.email?.[0]?.toUpperCase() || ''
  })

  constructor() {
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
        // No ?ns in URL → auto-select first namespace and navigate
        const first = nsList[0]
        if (first?.id) {
          this.router.navigate(['/agentos/home'], { queryParams: { ns: first.id }, replaceUrl: true })
        }
      }
    })
  }

  // ---------------------------------------------------------------------------
  // User interactions
  // ---------------------------------------------------------------------------

  protected toggleNsMenu(event?: MouseEvent): void {
    event?.stopPropagation()
    event?.preventDefault()
    this.nsMenuOpen.update((v) => !v)
  }

  protected closeNsMenu(event: MouseEvent): void {
    event.stopPropagation()
    this.nsMenuOpen.set(false)
  }

  protected onNamespaceSelected(ns: NamespaceListItem): void {
    this.selectedNamespace.set(ns)
    this.nsMenuOpen.set(false)
    this.router.navigate(['/agentos/home'], { queryParams: { ns: ns.id } })
  }

  protected onCaseSelected(caseId: string): void {
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId(), case: caseId },
    })
  }

  protected onCreateRequested(): void {
    // Navigate to home view (no ?case) so CaseHomeComponent renders
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId() },
    })
  }

  protected onDeleteCase(caseId: string): void {
    this.caseController.deleteCase(caseId).subscribe({
      next: () => this.refresh$.next(),
      error: (err) => console.error('Failed to delete case', caseId, err),
    })
  }

  protected onInterruptCase(caseId: string): void {
    this.caseController.interruptCase(caseId).subscribe({
      next: () => this.refresh$.next(),
      error: (err) => console.error('Failed to interrupt case', caseId, err),
    })
  }

  protected onKillCase(caseId: string): void {
    this.caseController.killCase(caseId).subscribe({
      next: () => this.refresh$.next(),
      error: (err) => console.error('Failed to kill case', caseId, err),
    })
  }

  protected navigateHome(): void {
    this.router.navigate(['/agentos/home'])
  }

  protected navigateToNamespaces(): void {
    this.router.navigate(['/agentos/namespaces'])
  }

  protected navigateToAdmin(): void {
    this.router.navigate(['/agentos/admin'])
  }

  protected navigateToProfile(): void {
    this.router.navigate(['/agentos/user'])
  }

  protected toggleTheme(): void {
    const next: ThemeMode = this.isDark() ? 'light' : 'dark'
    this.themePort.setTheme(next)
  }

  protected toggleMenu(event?: MouseEvent): void {
    event?.stopPropagation()
    event?.preventDefault()
    this.menuOpen.update((v) => !v)
  }

  protected closeMenu(event: MouseEvent): void {
    event.stopPropagation()
    this.menuOpen.set(false)
  }

  protected onMenuNavigate(path: string): void {
    this.menuOpen.set(false)
    this.router.navigate([path])
  }

  protected onMenuToggleTheme(): void {
    this.menuOpen.set(false)
    this.toggleTheme()
  }

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

  protected readonly activeCaseTitle = computed(() => {
    const id = this.activeCaseId()
    if (!id) return 'Nouveau case'
    const found = this.cases().find((c) => c.id === id)
    return found?.title ?? id
  })

  protected readonly activeCaseStatus = computed(() => {
    const id = this.activeCaseId()
    if (!id) return 'idle'
    const found = this.cases().find((c) => c.id === id)
    return this.deriveMobileStatus(found?.status)
  })

  protected readonly activeCaseStatusLabel = computed(() => {
    const id = this.activeCaseId()
    if (!id) return ''
    const found = this.cases().find((c) => c.id === id)
    return found?.status?.toLowerCase() ?? ''
  })

  protected deriveMobileStatus(status: CaseStatusEnum | undefined): string {
    switch (status) {
      case CaseStatusEnum.RUNNING:
        return 'run'
      case CaseStatusEnum.KILLED:
        return 'killed'
      case CaseStatusEnum.ERROR:
        return 'error'
      default:
        return 'idle'
    }
  }

  protected onMenuToggleLogs(): void {
    this.menuOpen.set(false)
    this.showTechnical.update((v) => !v)
  }

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
    }

    const onUp = (): void => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
    }

    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
  }

  /** Called after a case is created to refresh the sidebar list. */
  refreshCases(): void {
    this.refresh$.next()
  }
}
