import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
import { Router, RouterLink, RouterLinkActive } from '@angular/router'
import { UserStateService } from '../../services/user-state.service'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'

/**
 * ShellTopbarComponent — barre de navigation supérieure réutilisable.
 *
 * Extrait du shell-sidebar__top pour être partagé entre :
 * - La sidebar du CaseShell (home)
 * - Le layout admin (/agentos/admin/**)
 * - Le layout user (/agentos/me)
 *
 * Contient : logo Coday + navigation directe vers les espaces de configuration.
 */
@Component({
  selector: 'agentos-shell-topbar',
  templateUrl: './shell-topbar.component.html',
  styleUrl: './shell-topbar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
})
export class ShellTopbarComponent {
  private readonly router = inject(Router)
  private readonly userState = inject(UserStateService)
  private readonly namespaceState = inject(NamespaceStateService)

  protected readonly isAdmin = computed(() => this.userState.currentUser()?.isAdmin === true)

  protected navigateHome(): void {
    const nsId = this.namespaceState.activeNamespaceId()
    this.router.navigate(['/agentos/home'], nsId ? { queryParams: { ns: nsId } } : {})
  }
}
