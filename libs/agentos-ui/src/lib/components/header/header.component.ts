import { Component, DestroyRef, inject, OnInit } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { BackendStatusComponent } from '../backend-status/backend-status.component'
import { UserStateService } from '../../services/user-state.service'

/**
 * HeaderComponent — global navigation bar.
 *
 * Shared primitive consumed by layout components.
 * Each layout decides how and where to place it.
 *
 * The user avatar button (top-right) toggles navigation to /agentos/me.
 * When already on /me, it navigates back using browser history (contextual return).
 *
 * Loads the current user on init (no-op if already cached) so initials are
 * visible as soon as the header renders, without waiting for /me to be visited.
 */
@Component({
  selector: 'agentos-header',
  standalone: true,
  imports: [BackendStatusComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly userState = inject(UserStateService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly currentUser = this.userState.currentUser

  ngOnInit(): void {
    if (!this.currentUser()) {
      this.userState.loadMe().pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
    }
  }

  protected get userInitials(): string {
    const user = this.currentUser()
    if (!user) return ''
    const first = user.firstname?.[0] ?? ''
    const last = user.lastname?.[0] ?? ''
    return (first + last).toUpperCase() || user.email?.[0]?.toUpperCase() || '?'
  }

  protected get isOnProfilePage(): boolean {
    return this.router.url.startsWith('/agentos/me')
  }

  protected toggleProfile(): void {
    if (this.isOnProfilePage) {
      if (window.history.length > 1) {
        window.history.back()
      } else {
        this.router.navigate(['/agentos'])
      }
    } else {
      this.router.navigate(['/agentos/me'])
    }
  }
}
