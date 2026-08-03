import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { Case, NamespaceListItem } from '@whoz-oss/agentos-api-client'
import { CaseDrawerComponent } from '../../case-drawer/case-drawer.component'
import { ShellUserMenuComponent } from '../shell-user-menu/shell-user-menu.component'
import { BlueprintDirective } from '@whoz-oss/design-system'

/**
 * ShellSidebarComponent — desktop sidebar.
 *
 * Presentational component responsible for:
 * - Logo + home navigation
 * - User context menu (delegates to ShellUserMenuComponent)
 * - Namespace picker
 * - Case list (delegates to CaseDrawerComponent)
 *
 * All state and navigation logic stays in CaseShellComponent.
 *
 * Compact mode:
 * - Toggled by the chevron pill on the right border
 * - Reduces width to COMPACT_WIDTH (60px) and shows only case initials badges
 * - State is local to this component (no need to bubble up to the shell)
 */
@Component({
  selector: 'agentos-shell-sidebar',
  imports: [CaseDrawerComponent, ShellUserMenuComponent, BlueprintDirective],
  templateUrl: './shell-sidebar.component.html',
  styleUrl: './shell-sidebar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellSidebarComponent {
  protected brandHovered = false

  private static readonly COMPACT_WIDTH = 64

  /** Whether the case drawer is in compact (icons-only) mode. */
  protected readonly isCompact = signal(false)
  /** Filter text for the case list — driven by the search input in the top bar. */
  protected readonly caseFilter = signal('')

  // Fixed position for the user menu in compact mode
  protected readonly compactMenuTop = signal(0)
  protected readonly compactMenuLeft = signal(0)

  // Compact mode — search overlay (position: fixed, anchored to the right of the rail)
  protected readonly compactSearchOpen = signal(false)
  protected readonly compactSearchTop = signal(0)
  protected readonly compactSearchLeft = signal(0)

  // Compact mode — namespace menu (position: fixed)
  protected readonly compactNsMenuOpen = signal(false)
  protected readonly compactNsMenuTop = signal(0)
  protected readonly compactNsMenuLeft = signal(0)

  private readonly compactSearchInputRef = viewChild<ElementRef<HTMLInputElement>>('compactSearchInput')

  protected onCompactSearchBtnClick(event: MouseEvent): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    this.compactSearchTop.set(rect.top)
    this.compactSearchLeft.set(rect.right + 8)
    this.compactSearchOpen.update((v) => !v)
    if (!this.compactSearchOpen()) return
    setTimeout(() => this.compactSearchInputRef()?.nativeElement.focus(), 0)
  }

  protected onCompactSearchInput(value: string): void {
    this.caseFilter.set(value)
  }

  protected closeCompactSearch(): void {
    this.compactSearchOpen.set(false)
    // Do not reset caseFilter here — the filter stays active until the user clears it.
  }

  protected clearCompactSearch(): void {
    this.caseFilter.set('')
    this.compactSearchOpen.set(false)
  }

  protected onCompactNsBtnClick(event: MouseEvent): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    this.compactNsMenuTop.set(rect.top)
    this.compactNsMenuLeft.set(rect.right + 8)
    this.compactNsMenuOpen.update((v) => !v)
  }

  protected closeCompactNsMenu(): void {
    this.compactNsMenuOpen.set(false)
  }

  protected onCompactNsSelected(ns: NamespaceListItem): void {
    this.compactNsMenuOpen.set(false)
    this.namespaceSelected.emit(ns)
  }

  protected onCompactUserBtnClick(event: MouseEvent): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    // Menu opens upward — anchor to the bottom of the avatar button
    this.compactMenuTop.set(rect.bottom)
    this.compactMenuLeft.set(rect.right + 8)
    this.menuToggled.emit(event)
  }

  protected toggleCompact(): void {
    this.isCompact.update((v) => !v)
  }

  protected nsInitial(name: string | undefined | null): string {
    return (name ?? '?').charAt(0).toUpperCase()
  }

  /**
   * Effective sidebar width:
   * - 0 when sidebar is closed
   * - COMPACT_WIDTH (60px) when in compact mode
   * - sidebarWidth() otherwise
   */
  protected readonly effectiveWidth = computed(() => {
    if (!this.sidebarOpen()) return 0
    return this.isCompact() ? ShellSidebarComponent.COMPACT_WIDTH : this.sidebarWidth()
  })

  // Layout
  readonly sidebarWidth = input.required<number>()
  readonly sidebarOpen = input.required<boolean>()

  // Namespace
  readonly selectedNamespace = input.required<NamespaceListItem | null>()
  readonly namespaces = input.required<NamespaceListItem[]>()
  readonly showNsPicker = input.required<boolean>()
  readonly nsMenuOpen = input.required<boolean>()

  // Cases
  readonly cases = input.required<Case[]>()
  readonly activeCaseId = input.required<string | null>()

  // User menu
  readonly userInitials = input.required<string>()
  readonly userName = input.required<string>()
  readonly menuOpen = input.required<boolean>()
  readonly isAdmin = input.required<boolean>()
  readonly isDark = input.required<boolean>()
  readonly themeVariant = input.required<string>()
  readonly showTechnical = input.required<boolean>()

  // Outputs
  readonly collapseRequested = output<void>()
  readonly homeClicked = output<void>()
  readonly namespaceSelected = output<NamespaceListItem>()
  readonly nsMenuToggled = output<void>()
  readonly nsMenuClosed = output<Event>()
  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()
  readonly renameRequested = output<{ id: string; title: string }>()
  readonly menuToggled = output<MouseEvent>()
  readonly menuClosed = output<Event>()
  readonly navigateTo = output<string>()
  readonly themeToggled = output<void>()
  readonly themeVariantChanged = output<string>()
  readonly logsToggled = output<void>()
}
