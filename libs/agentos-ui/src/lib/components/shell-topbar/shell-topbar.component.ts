import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { THEME_PORT } from '../../services/theme.service'
import { UserStateService } from '../../services/user-state.service'
import { ShellUserMenuComponent } from '../case-shell/shell-user-menu/shell-user-menu.component'

/**
 * ShellTopbarComponent — barre de navigation supérieure réutilisable.
 *
 * Extrait du shell-sidebar__top pour être partagé entre :
 * - La sidebar du CaseShell (home)
 * - Le layout admin (/agentos/admin/**)
 * - Le layout user (/agentos/me)
 *
 * Contient : logo Coday + bouton utilisateur avec menu contextuel.
 */
@Component({
  selector: 'agentos-shell-topbar',
  templateUrl: './shell-topbar.component.html',
  styleUrl: './shell-topbar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ShellUserMenuComponent],
})
export class ShellTopbarComponent {
  private readonly router = inject(Router)
  private readonly namespaceState = inject(NamespaceStateService)
  private readonly userState = inject(UserStateService)
  private readonly themePort = inject(THEME_PORT)

  protected readonly menuOpen = signal(false)

  protected readonly isAdmin = computed(() => this.userState.currentUser()?.isAdmin === true)

  protected readonly isDark = computed(() => {
    const t = this.themePort.theme()
    if (t === 'dark') return true
    if (t === 'light') return false
    return typeof document !== 'undefined' && document.documentElement.hasAttribute('data-theme')
  })

  protected readonly userInitials = computed(() => {
    const user = this.userState.currentUser()
    if (!user) return ''
    const first = user.firstname?.[0] ?? ''
    const last = user.lastname?.[0] ?? ''
    return (first + last).toUpperCase() || user.email?.[0]?.toUpperCase() || ''
  })

  // showTechnical n'a pas de sens hors du CaseShell — on le passe à false
  protected readonly showTechnical = signal(false)

  protected navigateHome(): void {
    const nsId = this.namespaceState.activeNamespaceId()
    this.router.navigate(['/agentos/home'], nsId ? { queryParams: { ns: nsId } } : {})
  }

  protected toggleMenu(event: MouseEvent): void {
    event.stopPropagation()
    this.menuOpen.update((v) => !v)
  }

  protected closeMenu(event: Event): void {
    event.stopPropagation()
    this.menuOpen.set(false)
  }

  protected onNavigate(path: string): void {
    this.menuOpen.set(false)
    this.router.navigate([path])
  }

  protected onThemeToggle(): void {
    this.menuOpen.set(false)
    const next = this.isDark() ? 'light' : 'dark'
    this.themePort.setTheme(next as any)
  }

  protected onLogsToggle(): void {
    // no-op hors du CaseShell
    this.menuOpen.set(false)
  }
}
