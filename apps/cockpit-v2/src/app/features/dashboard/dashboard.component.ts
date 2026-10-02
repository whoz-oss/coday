import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { catchError, of } from 'rxjs'
import { CockpitV2ApiService } from '../../shared'

/**
 * Default Cockpit V2 view.
 *
 * Exercises the shared API service by displaying the backend service
 * descriptor. It is intentionally minimal — feature views are added on top of
 * this foundation.
 */
@Component({
  selector: 'cockpit-v2-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="dashboard">
      <h1 class="dashboard__title">Tableau de bord</h1>

      @if (info(); as service) {
        <article class="card" data-testid="service-info">
          <h2 class="card__title">{{ service.service }}</h2>
          <p class="card__line">Statut : {{ service.status }}</p>
          <p class="card__line">Version : {{ service.version }}</p>
          <ul class="card__endpoints">
            @for (endpoint of service.endpoints; track endpoint) {
              <li>{{ endpoint }}</li>
            }
          </ul>
        </article>
      } @else {
        <p class="dashboard__empty" data-testid="service-info-empty">Le service cockpit-v2 est indisponible.</p>
      }
    </section>
  `,
  styles: `
    .dashboard__title {
      margin: 0 0 16px;
      font-size: 22px;
      font-weight: 700;
    }

    .card {
      padding: 20px;
      border: 1px solid var(--cockpit-outline);
      border-radius: 12px;
      background: var(--cockpit-surface-2);
      max-width: 480px;
    }

    .card__title {
      margin: 0 0 12px;
      font-size: 16px;
      font-weight: 700;
      color: var(--cockpit-primary);
    }

    .card__line {
      margin: 4px 0;
      font-size: 14px;
    }

    .card__endpoints {
      margin: 12px 0 0;
      padding-left: 18px;
      font-size: 13px;
      color: var(--cockpit-text-muted);
    }

    .dashboard__empty {
      color: var(--cockpit-text-muted);
    }
  `,
})
export class DashboardComponent {
  private readonly api = inject(CockpitV2ApiService)

  /** Backend service descriptor; null when the API is unreachable. */
  protected readonly info = toSignal(this.api.getServiceInfo().pipe(catchError(() => of(null))), { initialValue: null })
}
