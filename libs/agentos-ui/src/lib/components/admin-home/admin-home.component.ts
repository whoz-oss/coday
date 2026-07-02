import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { Router } from '@angular/router'

/**
 * AdminHomeComponent — hub page for super-admin actions.
 *
 * Loaded at /agentos/admin. Lists the available admin sections:
 * - Users: manage all platform users
 * - Platform agents: manage platform-level agent configs (namespaceId IS NULL)
 *
 * Access is enforced by the backend (403). The frontend shows this page only
 * when the header button is visible (user.isAdmin === true).
 */
@Component({
  selector: 'agentos-admin-home',
  standalone: true,
  imports: [],
  templateUrl: './admin-home.component.html',
  styleUrl: './admin-home.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminHomeComponent {
  private readonly router = inject(Router)

  protected navigateTo(path: string): void {
    this.router.navigate([path])
  }
}
