import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
import { RouterLink, RouterLinkActive } from '@angular/router'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { ShellContextService } from '../../services/shell-context.service'
import { UserStateService } from '../../services/user-state.service'

/**
 * ShellTopbarComponent — navigation shared by every settings page (namespace, administration, profile).
 *
 * One bar to hop between all configuration scopes without going back through the cases view:
 * - "Back to <namespace>" is the one way out of every settings page, back to the work in progress: active
 *   namespace AND open case (or, if nothing was visited yet, the namespace the cases view opens by default). It is
 *   always the leftmost element (no logo: a settings page is not the app's home) and is left out when the user has
 *   no namespace to go back to.
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

  /** Label of the way out, naming the namespace it leads to; null (link hidden) while there is none to name. */
  protected readonly backLabel = computed(() => {
    const target = this.shellContext.backTarget()
    return target ? `Back to ${target.name}` : null
  })

  /** Query params restoring that namespace and its open case on the cases view. */
  protected readonly workQueryParams = computed(() =>
    this.shellContext.workQueryParams(this.shellContext.backTarget()?.id ?? null)
  )

  constructor() {
    this.shellContext.loadBackTarget()
  }
}
