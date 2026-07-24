import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
} from '@angular/core'
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { NamespaceControllerService, NamespaceListItem } from '@whoz-oss/agentos-api-client'
import { debounceTime, map, skip } from 'rxjs'
import { CaseChatComponent } from '../case-chat/case-chat.component'
import { CaseHomeComponent } from '../case-home/case-home.component'
import { THEME_PORT, ThemeMode } from '../../services/theme.service'
import { UserStateService } from '../../services/user-state.service'
import { CaseStateService } from '../../services/case-state.service'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
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
  private readonly namespaceState = inject(NamespaceStateService)
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

  protected readonly userName = computed(() => {
    const user = this.userState.currentUser()
    if (!user) return ''
    return [user.firstname, user.lastname].filter(Boolean).join(' ') || user.email || ''
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
  protected readonly sidebarWidth = signal(Number(localStorage.getItem('agentos.sidebar.width')) || 400)

  /** Whether the user context menu is open */
  protected readonly menuOpen = signal(false)

  /** Whether the namespace dropdown is open */
  protected readonly nsMenuOpen = signal(false)

  /** Whether the desktop sidebar is expanded — persisted in localStorage */
  protected readonly sidebarOpen = signal(localStorage.getItem('agentos.sidebar.open') !== 'false')

  /** Whether the mobile case drawer is open */
  protected readonly mobileDrawerOpen = signal(false)

  private static readonly SHOW_TECHNICAL_KEY = 'agentos.case-chat.showTechnical'

  /** Source de vérité globale pour les technical logs — persisté en localStorage. */
  protected readonly showTechnical = signal(localStorage.getItem(CaseShellComponent.SHOW_TECHNICAL_KEY) === 'true')

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
        // Sync into the shared service so other parts of the app (e.g. ShellTopbarComponent)
        // can read the current namespace without needing the query param.
        this.namespaceState.selectNamespace(nsId)
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
    const nsId = this.namespaceId()
    this.router.navigate(['/agentos/home'], nsId ? { queryParams: { ns: nsId } } : {})
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
    this.showTechnical.update((v) => {
      const next = !v
      localStorage.setItem(CaseShellComponent.SHOW_TECHNICAL_KEY, String(next))
      return next
    })
  }

  // ---------------------------------------------------------------------------
  // Mobile drawer
  // ---------------------------------------------------------------------------

  protected toggleMobileDrawer(): void {
    this.mobileDrawerOpen.update((v) => !v)
  }

  protected onMobileCaseSelect(caseId: string): void {
    this.mobileDrawerOpen.set(false)
    this.onCaseSelected(caseId)
  }

  protected onMobileCreateCase(): void {
    this.mobileDrawerOpen.set(false)
    this.onCreateRequested()
  }

  // ---------------------------------------------------------------------------
  // Sidebar resize
  // ---------------------------------------------------------------------------

  private readonly hostEl = inject(ElementRef<HTMLElement>)

  /**
   * Start a drag session to resize the sidebar.
   *
   * Strategy: manipulate the sidebar DOM width directly during the drag
   * (zero Angular change detection overhead), then commit to the signal
   * on mouseup so the rest of the app re-renders only once.
   */
  protected onResizeStart(event: MouseEvent): void {
    event.preventDefault()

    const startX = event.clientX
    const startWidth = this.sidebarWidth()
    const maxWidth = Math.floor(window.innerWidth * 0.5)

    // Grab the sidebar element directly — avoids going through Angular bindings
    const sidebarEl = this.hostEl.nativeElement.querySelector(
      'agentos-shell-sidebar > .shell-sidebar'
    ) as HTMLElement | null

    // Disable CSS transition during drag for immediate feedback
    if (sidebarEl) sidebarEl.style.transition = 'none'

    // Prevent text selection while dragging
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'

    let currentWidth = startWidth

    const onMove = (e: MouseEvent): void => {
      const delta = e.clientX - startX
      currentWidth = Math.max(200, Math.min(maxWidth, startWidth + delta))
      // Direct DOM mutation — no Angular involvement
      if (sidebarEl) sidebarEl.style.width = `${currentWidth}px`
    }

    const onUp = (): void => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)

      // Restore styles
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
      if (sidebarEl) sidebarEl.style.transition = ''

      // Commit final value to signal — single re-render + localStorage persist
      this.sidebarWidth.set(currentWidth)
    }

    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
  }
}
