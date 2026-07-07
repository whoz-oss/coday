import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { NamespaceListItem } from '@whoz-oss/agentos-api-client'
import { ShellUserMenuComponent } from '../shell-user-menu/shell-user-menu.component'

/**
 * ShellTopbarMobileComponent — mobile top bar.
 *
 * Responsible for:
 * - Logo + home navigation
 * - Namespace switcher (trigger + dropdown)
 * - User avatar + context menu (delegates to ShellUserMenuComponent)
 *
 * Visible only on mobile (hidden via CSS on desktop).
 */
@Component({
  selector: 'agentos-shell-topbar-mobile',
  imports: [ShellUserMenuComponent],
  templateUrl: './shell-topbar-mobile.component.html',
  styleUrl: './shell-topbar-mobile.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellTopbarMobileComponent {
  // ── Namespace ────────────────────────────────────────────
  readonly selectedNamespace = input.required<NamespaceListItem | null>()
  readonly namespaces = input.required<NamespaceListItem[]>()
  readonly nsMenuOpen = input.required<boolean>()

  // ── User menu ────────────────────────────────────────────
  readonly userInitials = input.required<string>()
  readonly menuOpen = input.required<boolean>()
  readonly isAdmin = input.required<boolean>()
  readonly isDark = input.required<boolean>()
  readonly showTechnical = input.required<boolean>()

  // ── Outputs ──────────────────────────────────────────────
  readonly homeClicked = output<void>()
  readonly namespaceSelected = output<NamespaceListItem>()
  readonly nsMenuToggled = output<MouseEvent>()
  readonly nsMenuClosed = output<Event>()
  readonly menuToggled = output<MouseEvent | undefined>()
  readonly menuClosed = output<Event>()
  readonly navigateTo = output<string>()
  readonly themeToggled = output<void>()
  readonly logsToggled = output<void>()
}
