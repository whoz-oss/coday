import { ChangeDetectionStrategy, Component } from '@angular/core'

/**
 * Placeholder for the History screen.
 *
 * The full table (`history-page.component`, `mat-table`, filters, CSV export)
 * is ported in a later wave; this component only exists so the route resolves
 * and the Shell renders.
 */
@Component({
  selector: 'sf-history-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="placeholder">
      <h1>Historique</h1>
      <p class="sf-muted">Écran à venir.</p>
    </section>
  `,
  styles: `
    .placeholder h1 {
      margin: 0 0 8px;
      font-size: 22px;
      font-weight: 800;
    }
    .placeholder p {
      margin: 0;
      font-size: 14px;
    }
  `,
})
export class HistoryPlaceholderComponent {}
