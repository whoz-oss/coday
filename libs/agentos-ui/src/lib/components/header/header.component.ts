import { Component } from '@angular/core'
import { BackendStatusComponent } from '../backend-status/backend-status.component'

/**
 * HeaderComponent — global navigation bar.
 *
 * Shared primitive consumed by layout components.
 * Each layout decides how and where to place it.
 *
 * TODO: add navigation icons (settings, user, back…) as the app grows
 */
@Component({
  selector: 'agentos-header',
  standalone: true,
  imports: [BackendStatusComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent {}
