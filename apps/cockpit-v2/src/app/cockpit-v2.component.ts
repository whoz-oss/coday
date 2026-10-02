import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { RouterOutlet } from '@angular/router'
import { catchError, of } from 'rxjs'
import { CockpitHeaderComponent, CockpitV2ApiService } from './shared'

/**
 * Root component of the Cockpit V2 application.
 *
 * Renders the shared header (fed with live backend status) and the routed
 * feature views through the router outlet.
 */
@Component({
  selector: 'cockpit-v2-root',
  imports: [RouterOutlet, CockpitHeaderComponent],
  templateUrl: './cockpit-v2.component.html',
  styleUrl: './cockpit-v2.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CockpitV2Component {
  private readonly api = inject(CockpitV2ApiService)

  /** Live backend status; null until it is fetched or if the call fails. */
  protected readonly status = toSignal(this.api.getStatus().pipe(catchError(() => of(null))), { initialValue: null })
}
