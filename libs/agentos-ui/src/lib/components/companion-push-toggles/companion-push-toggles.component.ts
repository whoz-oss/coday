import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { CompanionPipService } from '../../services/companion-pip.service'

/**
 * Self-contained "Companion" toggle menu item — injects its own service, no inputs or
 * outputs, so it can be dropped into any host menu template.
 *
 * Rendered inside ShellUserMenuComponent, which is itself shared by the desktop sidebar
 * (expanded + compact rail) and the topbar. Deliberately NOT wired into the mobile case
 * drawer: the companion is a Document Picture-in-Picture window, an API mobile browsers
 * don't implement, so there is nothing to toggle there.
 *
 * Angular's style encapsulation means ShellUserMenuComponent's `.shell-user-menu__item`
 * rules don't cascade in here, so the relevant declarations are duplicated into this
 * component's own stylesheet (kept in sync manually, small surface).
 *
 * NOTE — the name says "push" but only the Companion toggle is present: this component
 * is the shared insertion point for notification-channel toggles, and the Web Push
 * channel (PushSubscriptionService + backend PushNotificationDispatcher) lands in a
 * separate change. The file path and component name are deliberately kept as-is so that
 * change reintroduces its half here rather than adding a parallel component.
 */
@Component({
  selector: 'agentos-companion-push-toggles',
  templateUrl: './companion-push-toggles.component.html',
  styleUrl: './companion-push-toggles.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanionPushTogglesComponent {
  protected readonly companionPip = inject(CompanionPipService)

  // Static per session — browser API support doesn't change at runtime.
  protected readonly companionSupported = this.companionPip.isSupported()

  /**
   * Whether this component will render anything at all — read by host templates (via a
   * `#ref` template variable) to conditionally show their own separator, so a browser
   * without Document PiP doesn't leave a dangling empty divider. Kept as a distinct
   * member rather than inlining `companionSupported` at the call sites: it is the
   * contract the hosts depend on, and it stays correct when a second toggle is added.
   */
  readonly hasAnyItem = this.companionSupported
}
