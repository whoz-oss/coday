import { ChangeDetectionStrategy, Component, input } from '@angular/core'

/**
 * Placeholder for the Session detail screen (`/sessions/:runId`).
 *
 * `runId` is bound from the route thanks to `withComponentInputBinding()`. The
 * full timeline (`session-page.component`, `agent-timeline`, `event-log`) is
 * ported in a later wave.
 */
@Component({
  selector: 'sf-session-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="placeholder">
      <h1>Session</h1>
      <p class="sf-muted">
        Run <span class="sf-id">{{ runId() }}</span> — écran à venir.
      </p>
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
export class SessionPlaceholderComponent {
  readonly runId = input<string>('')
}
