import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { BrowserNotificationService } from '../../services/browser-notification.service'
import { CompanionPipService } from '../../services/companion-pip.service'

/**
 * Self-contained "Notifications" and "Companion" toggle menu items — injects its own
 * services, no inputs or outputs, so it can be dropped into any host menu template.
 *
 * Rendered inside ShellUserMenuComponent, which is shared across the desktop sidebar
 * (expanded + compact rail), the topbar, and the mobile menu.
 */
@Component({
  selector: 'agentos-companion-push-toggles',
  templateUrl: './companion-push-toggles.component.html',
  styleUrl: './companion-push-toggles.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanionPushTogglesComponent {
  protected readonly browserNotifications = inject(BrowserNotificationService)
  protected readonly companionPip = inject(CompanionPipService)

  // Static per session — browser API support doesn't change at runtime.
  protected readonly notificationsSupported = this.browserNotifications.isSupported()
  protected readonly companionSupported = this.companionPip.isSupported()

  /**
   * Whether this component will render anything at all — read by host templates (via a
   * `#ref` template variable) to conditionally show their own separator, so a browser
   * without either API doesn't leave a dangling empty divider.
   */
  readonly hasAnyItem = this.notificationsSupported || this.companionSupported
}
