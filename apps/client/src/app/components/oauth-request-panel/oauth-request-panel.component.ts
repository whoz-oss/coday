import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { MatIconModule } from '@angular/material/icon'
import { OAuthRequestEvent } from '@coday/model'
import { OAuthService } from '../../core/services/oauth.service'

/**
 * Inline OAuth authorization panel shown in the conversation input area
 * when an OAuthRequestEvent is received from the server.
 *
 * The popup is opened from a direct user click so browsers never block it.
 * Follows the same visual pattern as choice-select.
 */
@Component({
  selector: 'app-oauth-request-panel',
  standalone: true,
  imports: [MatIconModule],
  templateUrl: './oauth-request-panel.component.html',
  styleUrl: './oauth-request-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OAuthRequestPanelComponent {
  private readonly oauthService = inject(OAuthService)

  readonly pendingRequest = toSignal(this.oauthService.pendingRequest$, { initialValue: null })

  onAuthorize(event: OAuthRequestEvent): void {
    this.oauthService.openPopup(event)
  }

  onCancel(event: OAuthRequestEvent): void {
    this.oauthService.cancelRequest(event)
  }
}
