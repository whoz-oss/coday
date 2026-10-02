import { ChangeDetectionStrategy, Component } from '@angular/core'

/**
 * Placeholder for the Sandboxes screen.
 *
 * The full dashboard (`sandboxes-page.component`, sandbox cards, launch form)
 * is ported in a later wave; this component only exists so the route resolves
 * and the Shell renders.
 */
@Component({
  selector: 'sf-sandboxes-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="placeholder">
      <h1>Sandboxes</h1>
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
export class SandboxesPlaceholderComponent {}
