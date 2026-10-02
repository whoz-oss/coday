import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { CockpitV2Status } from '../../models/cockpit-v2.model'

/**
 * Shared application header for Cockpit V2.
 *
 * Displays the application title and, when available, the live backend status.
 * Standalone and signal-based so it can be reused across feature views.
 */
@Component({
  selector: 'cockpit-v2-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="cockpit-header">
      <span class="cockpit-header__title">{{ title() }}</span>
      @if (status()) {
        <span class="cockpit-header__status" data-testid="cockpit-status">
          <span class="cockpit-header__dot" aria-hidden="true"></span>
          {{ status()?.status }} · v{{ status()?.version }}
        </span>
      } @else {
        <span class="cockpit-header__status cockpit-header__status--offline" data-testid="cockpit-status-offline">
          indisponible
        </span>
      }
    </header>
  `,
  styles: `
    .cockpit-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 16px 24px;
      background: var(--cockpit-surface);
      border-bottom: 1px solid var(--cockpit-outline);
    }

    .cockpit-header__title {
      font-size: 18px;
      font-weight: 700;
      letter-spacing: 0.02em;
      color: var(--cockpit-text);
    }

    .cockpit-header__status {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      font-weight: 600;
      color: var(--cockpit-success);
    }

    .cockpit-header__status--offline {
      color: var(--cockpit-text-muted);
    }

    .cockpit-header__dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: currentColor;
    }
  `,
})
export class CockpitHeaderComponent {
  /** Application title shown in the header. */
  readonly title = input('Cockpit V2')

  /** Optional live status fetched from the backend. */
  readonly status = input<CockpitV2Status | null>(null)
}
