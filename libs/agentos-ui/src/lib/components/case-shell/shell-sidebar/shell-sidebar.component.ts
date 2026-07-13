import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
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

  // ── Layout ──────────────────────────────────────────────
  readonly sidebarWidth = input.required<number>()
  readonly sidebarOpen = input.required<boolean>()

  // ── Namespace ────────────────────────────────────────────
  readonly selectedNamespace = input.required<NamespaceListItem | null>()
  readonly namespaces = input.required<NamespaceListItem[]>()
  readonly showNsPicker = input.required<boolean>()
  readonly nsMenuOpen = input.required<boolean>()

  // ── Cases ────────────────────────────────────────────────
  readonly cases = input.required<Case[]>()
  readonly activeCaseId = input.required<string | null>()

  // ── User menu ────────────────────────────────────────────
  readonly userInitials = input.required<string>()
  readonly menuOpen = input.required<boolean>()
  readonly isAdmin = input.required<boolean>()
  readonly isDark = input.required<boolean>()
  readonly showTechnical = input.required<boolean>()

  // ── Outputs ──────────────────────────────────────────────
  readonly collapseRequested = output<void>()
  readonly homeClicked = output<void>()
  readonly namespaceSelected = output<NamespaceListItem>()
  readonly nsMenuToggled = output<void>()
  readonly nsMenuClosed = output<Event>()
  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly menuToggled = output<MouseEvent>()
  readonly menuClosed = output<Event>()
  readonly navigateTo = output<string>()
  readonly themeToggled = output<void>()
  readonly logsToggled = output<void>()
}
