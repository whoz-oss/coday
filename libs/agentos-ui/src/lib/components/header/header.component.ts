import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { NavigationEnd, Router } from '@angular/router'
import { filter, map } from 'rxjs'
import { BackendStatusComponent } from '../backend-status/backend-status.component'
import { UserStateService } from '../../services/user-state.service'

/**
 * HeaderComponent — global navigation bar.
 *
 * Shared primitive consumed by layout components.
 * Each layout decides how and where to place it.
 *
 * The user avatar button (top-right) toggles navigation to /agentos/user.
 * When already on /user, it navigates back using browser history (contextual return).
 *
 * Loads the current user on init (no-op if already cached) so initials are
 * visible as soon as the header renders, without waiting for /user to be visited.
 */
@Component({
  selector: 'agentos-header',
  imports: [BackendStatusComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HeaderComponent {
  private readonly router = inject(Router)
  private readonly userState = inject(UserStateService)

  protected readonly currentUser = this.userState.currentUser
  protected readonly isAdmin = computed(() => this.currentUser()?.isAdmin === true)

  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects)
    ),
    { initialValue: this.router.url }
  )

  protected readonly isOnProfilePage = computed(() => this.currentUrl().startsWith('/agentos/user'))

  protected readonly userInitials = computed(() => {
    const user = this.currentUser()
    if (!user) return ''
    const first = user.firstname?.[0] ?? ''
    const last = user.lastname?.[0] ?? ''
    return (first + last).toUpperCase() || user.email?.[0]?.toUpperCase() || '?'
  })

  constructor() {
    if (!this.currentUser()) {
      this.userState.loadMe().subscribe()
    }
  }

  protected navigateHome(): void {
    this.router.navigate(['/agentos/home'])
  }

  protected navigateToAdmin(): void {
    this.router.navigate(['/agentos/admin'])
  }

  protected toggleProfile(): void {
    if (this.isOnProfilePage()) {
      if (window.history.length > 1) {
        window.history.back()
      } else {
        this.router.navigate(['/agentos/home'])
      }
    } else {
      this.router.navigate(['/agentos/user'])
    }
  }
}
