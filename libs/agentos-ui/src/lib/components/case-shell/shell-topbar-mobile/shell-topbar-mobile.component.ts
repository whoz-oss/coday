import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { NamespaceListItem } from '@whoz-oss/agentos-api-client'

/**
 * ShellTopbarMobileComponent — mobile top bar.
 *
 *
 */
@Component({
  selector: 'agentos-shell-topbar-mobile',
  templateUrl: './shell-topbar-mobile.component.html',
  styleUrl: './shell-topbar-mobile.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellTopbarMobileComponent {
  // ── Namespace ────────────────────────────────────────────
  readonly selectedNamespace = input.required<NamespaceListItem | null>()
  readonly namespaces = input.required<NamespaceListItem[]>()
  readonly nsMenuOpen = input.required<boolean>()

  // ── Outputs ──────────────────────────────────────────────
  /** Ouvre/ferme le drawer mobile (liste des cases) */
  readonly drawerToggled = output<void>()
  readonly createRequested = output<void>()
  readonly namespaceSelected = output<NamespaceListItem>()
  readonly nsMenuToggled = output<MouseEvent>()
  readonly nsMenuClosed = output<Event>()
}
