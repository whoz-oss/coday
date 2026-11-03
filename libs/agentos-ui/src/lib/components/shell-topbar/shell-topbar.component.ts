import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
import { RouterLink, RouterLinkActive } from '@angular/router'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { ShellContextService } from '../../services/shell-context.service'
import { UserStateService } from '../../services/user-state.service'

/**
 * ShellTopbarComponent — navigation shared by every settings page (namespace, administration, profile).
 *
 * One bar to hop between all configuration scopes without going back through the cases view:
 * - "Cases" (and the logo) return to the work in progress: active namespace AND open case
 * - "Namespace settings" jumps straight to the active namespace configuration
 * - "Namespaces", "User settings" and "Administration" reach the other scopes
 *
 * Links target the real root URLs (`/home`, `/user`, ...) rather than the legacy `/agentos/...` prefix: it spares
 * the redirect and lets `routerLinkActive` match the URL actually displayed.
 */
@Component({
  selector: 'agentos-shell-topbar',
  templateUrl: './shell-topbar.component.html',
  styleUrl: './shell-topbar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
})
export class ShellTopbarComponent {
  private readonly userState = inject(UserStateService)
  private readonly namespaceState = inject(NamespaceStateService)
  private readonly shellContext = inject(ShellContextService)

  protected readonly isAdmin = computed(() => this.userState.currentUser()?.isAdmin === true)

  /** Namespace the user is working in; null until a cases or namespace settings page has resolved one. */
  protected readonly activeNamespaceId = this.namespaceState.activeNamespaceId

  /** Query params restoring the active namespace and open case on the cases view. */
  protected readonly workQueryParams = computed(() => this.shellContext.workQueryParams())
}
