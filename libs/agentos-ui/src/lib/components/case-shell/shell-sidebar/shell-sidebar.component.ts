import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { Case, NamespaceListItem } from '@whoz-oss/agentos-api-client'
import { CaseDrawerComponent } from '../../case-drawer/case-drawer.component'
import { ShellUserMenuComponent } from '../shell-user-menu/shell-user-menu.component'

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
  imports: [CaseDrawerComponent, ShellUserMenuComponent],
  templateUrl: './shell-sidebar.component.html',
  styleUrl: './shell-sidebar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellSidebarComponent {
  protected brandHovered = false

  private static readonly COMPACT_WIDTH = 60

  /** Whether the case drawer is in compact (icons-only) mode. */
  protected readonly isCompact = signal(false)

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
  readonly menuOpen = input.required<boolean>()
  readonly isAdmin = input.required<boolean>()
  readonly isDark = input.required<boolean>()
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
  readonly menuToggled = output<MouseEvent>()
  readonly menuClosed = output<Event>()
  readonly navigateTo = output<string>()
  readonly themeToggled = output<void>()
  readonly logsToggled = output<void>()
}
